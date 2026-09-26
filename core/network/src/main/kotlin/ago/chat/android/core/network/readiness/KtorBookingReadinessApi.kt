package ago.chat.android.core.network.readiness

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.readiness.BookingPrecondition
import ago.chat.android.core.domain.readiness.BookingReadinessApi
import ago.chat.android.core.domain.readiness.BookingReadinessResult
import ago.chat.android.core.domain.readiness.CalendarReadiness
import ago.chat.android.core.domain.readiness.PreconditionState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `26-163`: the adapter behind [BookingReadinessApi] — `GET /api/v1/console/booking-readiness`, the
 * identical check-[calendarApiBaseUrl]-first, classify-never-invent shape
 * [ago.chat.android.core.network.workers.KtorWorkersApi] and its siblings already establish.
 *
 * **A single read, no writes.** The "Исправить" action this port's own caller offers is a client-side
 * navigation swap to another config screen, never a call through this adapter
 * ([ago.chat.android.bookings.ReadinessViewModel]'s own doc comment) — so unlike [KtorWorkersApi] this
 * class has no `write` helper at all.
 */
public class KtorBookingReadinessApi(
    private val client: HttpClient,
    private val calendarApiBaseUrl: String?,
) : BookingReadinessApi {
    /**
     * `GET /api/v1/console/booking-readiness` — one entry per calendar the tenant has created, or the
     * server's own single synthetic entry (`calendarId == null`) for a tenant with none
     * (`Ago.Calendar.Contracts.CalendarReadinessResponse`'s own doc comment). A `200` whose body is not
     * the promised shape is a failure, never an empty list — the identical
     * `KtorWorkersApi.fetchWorkers`/`shapeGuard.ts` lesson read onto this endpoint.
     */
    override suspend fun fetchReadiness(): BookingReadinessResult {
        val baseUrl = calendarApiBaseUrl ?: return BookingReadinessResult.NotConfigured

        val response =
            try {
                client.get("$baseUrl/api/v1/console/booking-readiness")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return BookingReadinessResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            return BookingReadinessResult.Failed(BookingsQueueFailure.Unexpected)
        }

        return try {
            BookingReadinessResult.Loaded(response.body<List<CalendarReadinessWireDto>>().map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            BookingReadinessResult.Failed(classify(failure))
        }
    }
}

/** `Ago.Calendar.Contracts.CalendarReadinessResponse`, field for field. */
@Serializable
private data class CalendarReadinessWireDto(
    val calendarId: String? = null,
    val calendarName: String? = null,
    val isBookable: Boolean,
    val preconditions: List<PreconditionWireDto> = emptyList(),
)

/** `Ago.Calendar.Contracts.PreconditionStateResponse`, field for field — `precondition` is the domain
 * enum's wire name verbatim, classified by [toBookingPrecondition] rather than carried as a raw string. */
@Serializable
private data class PreconditionWireDto(
    val precondition: String,
    val isMet: Boolean,
)

private fun CalendarReadinessWireDto.toDomain() =
    CalendarReadiness(
        calendarId = calendarId,
        calendarName = calendarName,
        isBookable = isBookable,
        preconditions = preconditions.map { it.toDomain() },
    )

private fun PreconditionWireDto.toDomain() =
    PreconditionState(
        precondition = precondition.toBookingPrecondition(),
        rawPrecondition = precondition,
        isMet = isMet,
    )

/** `Ago.Calendar.Contracts.PreconditionStateResponse.precondition`'s six wire spellings, classified here
 * rather than left as a raw string for the UI to switch on — `26-163`'s own scope. An unrecognised
 * spelling becomes [BookingPrecondition.Unknown]; [PreconditionState.rawPrecondition] still carries it
 * verbatim, so a caller that wants to show it rather than drop it can. */
private fun String.toBookingPrecondition(): BookingPrecondition =
    when (this) {
        "WorkerOnCalendar" -> BookingPrecondition.WorkerOnCalendar
        "ServiceOffered" -> BookingPrecondition.ServiceOffered
        "WorkingHoursConfigured" -> BookingPrecondition.WorkingHoursConfigured
        "ScheduleSaved" -> BookingPrecondition.ScheduleSaved
        "SlotsMaterialized" -> BookingPrecondition.SlotsMaterialized
        "CalendarPublished" -> BookingPrecondition.CalendarPublished
        else -> BookingPrecondition.Unknown
    }

/** See `KtorWorkersApi`'s own `classify` for why this exists instead of a `describe()` copy. */
private fun classify(failure: Exception): BookingsQueueFailure =
    if (failure is IOException) BookingsQueueFailure.Transport else BookingsQueueFailure.Unexpected
