package ago.chat.android.core.network.calendarsetup

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.calendarsetup.CalendarDraft
import ago.chat.android.core.domain.calendarsetup.CalendarSetupApi
import ago.chat.android.core.domain.calendarsetup.ConfiguredCalendar
import ago.chat.android.core.domain.calendarsetup.TenantSetup
import ago.chat.android.core.domain.calendarsetup.TenantSetupResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `26-141`: the adapter behind [CalendarSetupApi] — the same "the whole status-code-to-meaning mapping
 * lives here, and only here" shape
 * [ago.chat.android.core.network.schedule.KtorWorkingHoursApi]'s own doc comment states, and the same
 * check-[calendarApiBaseUrl]-first, classify-never-invent discipline.
 *
 * **Never a raw exception class name or a hostname on screen** — [classify] answers only "no network"
 * or "something else", by exception type, never by printing `this::class.simpleName` or the exception's
 * own `message` (`UnknownHostException`'s message quotes the host it failed to resolve).
 */
public class KtorCalendarSetupApi(
    private val client: HttpClient,
    private val calendarApiBaseUrl: String?,
) : CalendarSetupApi {
    override suspend fun fetchSetup(): TenantSetupResult {
        val baseUrl = calendarApiBaseUrl ?: return TenantSetupResult.NotConfigured

        val response =
            try {
                client.get("$baseUrl/api/v1/console/configuration")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return TenantSetupResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            return TenantSetupResult.Failed(BookingsQueueFailure.Unexpected)
        }

        return try {
            TenantSetupResult.Loaded(response.body<TenantConfigurationWireDto>().toSetup())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty setup" - the identical
            // lesson `KtorBookingsApi.fetchPendingQueue` records for its own empty-looking failure.
            TenantSetupResult.Failed(classify(failure))
        }
    }

    override suspend fun saveAllowedOrigins(origins: List<String>): BookingActionResult =
        write {
            client.put("$it/api/v1/console/configuration/allowed-origins") {
                // Required for the reason `KtorConversationsApi.markRead`'s own doc comment gives:
                // Ktor's ContentNegotiation only serializes a body it can match against a declared
                // Content-Type.
                contentType(ContentType.Application.Json)
                setBody(SetAllowedOriginsWireDto(origins))
            }
        }

    override suspend fun createCalendar(
        draft: CalendarDraft,
        timeZone: String,
    ): BookingActionResult =
        write {
            client.post("$it/api/v1/console/calendars") {
                contentType(ContentType.Application.Json)
                setBody(CreateCalendarWireDto(name = draft.name, timeZone = timeZone, publish = draft.published))
            }
        }

    override suspend fun updateCalendar(
        calendarId: String,
        draft: CalendarDraft,
    ): BookingActionResult {
        require(calendarId.isNotBlank()) { "A calendar id is never blank." }
        return write {
            client.put("$it/api/v1/console/calendars/$calendarId") {
                contentType(ContentType.Application.Json)
                // No timeZone: a calendar's zone is create-only, and the server contract has no field
                // for it here.
                setBody(UpdateCalendarWireDto(name = draft.name, publish = draft.published))
            }
        }
    }

    /**
     * The one place the three writes' shared shape lives — they differ only in verb, path and body.
     * Each answers a bare `2xx` on success (`204` for the two `PUT`s, `201` for the calendar `POST`),
     * so the body is never read: this app re-reads [fetchSetup] after every write rather than patching
     * one row from an echo, and the create's `{calendarId}` is not needed for that. A non-2xx whose body
     * carries a genuine RFC 7807 `detail` is [BookingActionResult.Refused], shown verbatim; everything
     * else is [BookingActionResult.Failed], never a fabricated sentence.
     *
     * A `null` [calendarApiBaseUrl] here is unreachable in practice — the screen that calls this only
     * has controls on it once [fetchSetup] returned [TenantSetupResult.Loaded] against the same non-null
     * base URL — so it is [BookingsQueueFailure.Unexpected] rather than a fourth arm invented for a case
     * with no way to occur, the identical reasoning `KtorWorkingHoursApi.change` records.
     */
    private suspend fun write(request: suspend (String) -> HttpResponse): BookingActionResult {
        val baseUrl = calendarApiBaseUrl ?: return BookingActionResult.Failed(BookingsQueueFailure.Unexpected)

        val response =
            try {
                request(baseUrl)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return BookingActionResult.Failed(classify(failure))
            }

        if (response.status.isSuccess()) {
            return BookingActionResult.Succeeded
        }

        val detail =
            try {
                response.body<ProblemDetailsWireDto>().detail
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }

        return detail?.let { BookingActionResult.Refused(it) }
            ?: BookingActionResult.Failed(BookingsQueueFailure.Unexpected)
    }
}

/** `Ago.Calendar.Contracts.SetAllowedOriginsRequest` — the whole list, replaced every time. */
@Serializable
private data class SetAllowedOriginsWireDto(
    val origins: List<String>,
)

/** `Ago.Calendar.Contracts.CreateCalendarRequest` — the only calendar write that carries a timezone. */
@Serializable
private data class CreateCalendarWireDto(
    val name: String,
    val timeZone: String,
    val publish: Boolean,
)

/** `Ago.Calendar.Contracts.UpdateCalendarRequest` — name and published flag, and deliberately no
 * timezone: a calendar's zone is fixed at creation. */
@Serializable
private data class UpdateCalendarWireDto(
    val name: String,
    val publish: Boolean,
)

/**
 * `Ago.Calendar.Contracts.TenantConfigurationResponse`, reduced to the four things the Setup screen
 * draws — `ignoreUnknownKeys` (`AgoHttpClient.kt`'s own `agoJson`) is what lets this omit the workers,
 * the services and the per-calendar working hours rather than declare and discard them.
 */
@Serializable
private data class TenantConfigurationWireDto(
    val tenantName: String,
    val publicKey: String,
    val allowedOrigins: List<String> = emptyList(),
    val calendars: List<ConfiguredCalendarWireDto> = emptyList(),
)

@Serializable
private data class ConfiguredCalendarWireDto(
    val calendarId: String,
    val name: String,
    val timeZone: String,
    val isPublished: Boolean = false,
)

private fun TenantConfigurationWireDto.toSetup() =
    TenantSetup(
        tenantName = tenantName,
        publicKey = publicKey,
        allowedOrigins = allowedOrigins,
        calendars =
            calendars.map { calendar ->
                ConfiguredCalendar(
                    id = calendar.calendarId,
                    name = calendar.name,
                    timeZone = calendar.timeZone,
                    published = calendar.isPublished,
                )
            },
    )

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared
 * copy `KtorWorkingHoursApi`'s own private `ProblemDetailsWireDto` already establishes, restated here
 * rather than imported across adapters for the same reason that copy is not shared with its siblings. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/** See `KtorBookingsApi`'s own `classify` for why this exists instead of a `describe()` copy. */
private fun classify(failure: Exception): BookingsQueueFailure =
    if (failure is IOException) BookingsQueueFailure.Transport else BookingsQueueFailure.Unexpected
