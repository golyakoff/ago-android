package ago.chat.android.core.network.bookings

import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.PendingBooking
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `26-48`: the adapter behind [BookingsApi] — the same "the whole status-code-to-meaning mapping lives
 * here, and only here" shape `KtorConversationsApi`'s own doc comment states for the conversation
 * queue.
 *
 * `X-Ago-Active-Site` and the bearer token are attached by client plugins (`installAgoRestDefaults`),
 * not threaded through this method either — the identical reason `KtorConversationsApi` gives.
 *
 * **[calendarApiBaseUrl] is checked first, before any network call.** A `null` base URL means this
 * deployment does not run AGO Calendar at all — [PendingBookingsResult.NotConfigured] is returned
 * immediately, with zero requests made, the same `requireBaseUrl()`-before-`fetch` shape
 * `ago-console`'s own `calendarApi.ts` already establishes for the identical fact
 * (`CalendarApiError.NotConfigured`, thrown before the browser's own `fetch` ever runs).
 *
 * **Never a raw exception class name or a hostname on screen** — `docs/backlog/26-59-*.md` states that
 * rule for the whole app and has not landed it yet; this adapter is written to already obey it rather
 * than adding a fifth private `describe(): String` copy for `26-59` to later delete. [classify] answers
 * only "no network" or "something else", by [Exception] type — never by printing
 * `this::class.simpleName` or the exception's own `message`, both of which can carry exactly the
 * deployment's own hostname `26-59` found leaking (`UnknownHostException`'s own message quotes the host
 * it failed to resolve).
 */
public class KtorBookingsApi(
    private val client: HttpClient,
    private val calendarApiBaseUrl: String?,
) : BookingsApi {
    /** `GET /api/v1/console/pending-bookings` against `Ago.Calendar.Api`'s own origin. */
    override suspend fun fetchPendingQueue(): PendingBookingsResult {
        val baseUrl = calendarApiBaseUrl ?: return PendingBookingsResult.NotConfigured

        val response =
            try {
                client.get("$baseUrl/api/v1/console/pending-bookings")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return PendingBookingsResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            return PendingBookingsResult.Failed(BookingsQueueFailure.Unexpected)
        }

        return try {
            PendingBookingsResult.Loaded(response.body<List<PendingBookingWireDto>>().map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty queue" - the identical
            // `KtorConversationsApi`/`shapeGuard.ts` lesson, read onto this endpoint.
            PendingBookingsResult.Failed(classify(failure))
        }
    }
}

/** See this file's own class-level doc comment for why this exists instead of a `describe()` copy. An
 * [IOException] (no route, DNS failure, a dropped socket, a timeout — every transport-layer failure
 * Ktor's OkHttp engine surfaces) is the one thing worth calling "no network"; a shape mismatch on an
 * otherwise-successful response is genuinely a different problem and falls into [BookingsQueueFailure.Unexpected]
 * alongside every non-2xx status. */
private fun classify(failure: Exception): BookingsQueueFailure =
    if (failure is IOException) BookingsQueueFailure.Transport else BookingsQueueFailure.Unexpected

/** `Ago.Calendar.Contracts.PendingBookingResponse`, reduced to the fields [PendingBooking] carries —
 * see that class's own doc comment for which fields this wave has no screen for yet, and why
 * `ignoreUnknownKeys` (`AgoHttpClient.kt`'s own `agoJson`) is what lets this DTO simply omit them
 * rather than declare and immediately discard `customerId`/`localDate`/`isOverdue`/`phone`/`masked`. */
@Serializable
private data class PendingBookingWireDto(
    val bookingId: String,
    val calendarId: String,
    val workerId: String,
    val serviceId: String,
    val startsAt: String,
    val endsAt: String,
    val confirmationDeadline: String,
)

private fun PendingBookingWireDto.toDomain() =
    PendingBooking(
        bookingId = bookingId,
        calendarId = calendarId,
        workerId = workerId,
        serviceId = serviceId,
        startsAt = startsAt,
        endsAt = endsAt,
        confirmationDeadline = confirmationDeadline,
    )
