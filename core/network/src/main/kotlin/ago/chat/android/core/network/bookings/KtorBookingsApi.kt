package ago.chat.android.core.network.bookings

import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmedBooking
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.core.domain.bookings.ContactsResult
import ago.chat.android.core.domain.bookings.PendingBooking
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `26-48`: the adapter behind [BookingsApi] — the same "the whole status-code-to-meaning mapping lives
 * here, and only here" shape `KtorConversationsApi`'s own doc comment states for the conversation
 * queue. `26-51` adds [fetchConfirmedBookings] to this same class rather than a second adapter, since
 * both methods share every one of the properties this doc comment states — same base URL, same
 * not-configured check, same classification. `26-52` adds [fetchContacts] here for the identical
 * reason, not a fourth adapter.
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

    /**
     * `26-51`: `GET /api/v1/console/confirmed-bookings?from=&to=` — the identical
     * check-base-URL-first, classify-never-invent shape [fetchPendingQueue] above already establishes,
     * restated for this second endpoint rather than factored out: the two reads share no request shape
     * beyond "a `GET` against this same base URL", and a shared helper parameterised over a wire DTO
     * type would buy less clarity than it costs.
     */
    override suspend fun fetchConfirmedBookings(
        from: String,
        to: String,
    ): ConfirmedBookingsResult {
        val baseUrl = calendarApiBaseUrl ?: return ConfirmedBookingsResult.NotConfigured

        val response =
            try {
                client.get("$baseUrl/api/v1/console/confirmed-bookings") {
                    parameter("from", from)
                    parameter("to", to)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ConfirmedBookingsResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            return ConfirmedBookingsResult.Failed(BookingsQueueFailure.Unexpected)
        }

        return try {
            ConfirmedBookingsResult.Loaded(response.body<List<ConfirmedBookingWireDto>>().map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ConfirmedBookingsResult.Failed(classify(failure))
        }
    }

    /**
     * `26-52`: `GET /api/v1/console/contacts` — the identical check-base-URL-first,
     * classify-never-invent shape [fetchPendingQueue]/[fetchConfirmedBookings] above already establish,
     * restated for this third endpoint for the same reason [fetchConfirmedBookings]'s own doc comment
     * gives for not factoring the three into one shared helper.
     */
    override suspend fun fetchContacts(): ContactsResult {
        val baseUrl = calendarApiBaseUrl ?: return ContactsResult.NotConfigured

        val response =
            try {
                client.get("$baseUrl/api/v1/console/contacts")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ContactsResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            return ContactsResult.Failed(BookingsQueueFailure.Unexpected)
        }

        return try {
            ContactsResult.Loaded(response.body<List<ContactWireDto>>().map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ContactsResult.Failed(classify(failure))
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

/** `Ago.Calendar.Contracts.ConfirmedBookingResponse`, reduced to the fields [ConfirmedBooking]
 * carries — `phone`/`masked` are on the wire and simply omitted here, the identical
 * `ignoreUnknownKeys`-backed reduction [PendingBookingWireDto]'s own doc comment explains, applied for
 * the identical reason ([ConfirmedBooking]'s own doc comment: the masked-phone reveal is `26-53`, not
 * this item). */
@Serializable
private data class ConfirmedBookingWireDto(
    val bookingId: String,
    val calendarId: String,
    val workerId: String,
    val workerDisplayName: String,
    val serviceId: String,
    val serviceName: String?,
    val customerId: String,
    val customerDisplayName: String?,
    val startsAt: String,
    val endsAt: String,
    val localDate: String,
    val weekday: Int,
)

private fun ConfirmedBookingWireDto.toDomain() =
    ConfirmedBooking(
        bookingId = bookingId,
        calendarId = calendarId,
        workerId = workerId,
        workerDisplayName = workerDisplayName,
        serviceId = serviceId,
        serviceName = serviceName,
        customerId = customerId,
        customerDisplayName = customerDisplayName,
        startsAt = startsAt,
        endsAt = endsAt,
        localDate = localDate,
        weekday = weekday,
    )

/** `Ago.Calendar.Contracts.ContactResponse`, reduced to the fields [Contact] carries — `notes`/
 * `firstSeenAt`/`lastSeenAt`/`duplicatePhoneCustomerIds` are all on the wire and simply omitted here,
 * the identical `ignoreUnknownKeys`-backed reduction [PendingBookingWireDto]'s own doc comment
 * explains, applied for the identical reason [Contact]'s own doc comment gives. */
@Serializable
private data class ContactWireDto(
    val customerId: String,
    val phone: String,
    val masked: Boolean,
    val displayName: String?,
    val noShowCount: Int,
    val phoneVerifiedAt: String?,
    val phoneConfirmedByOperatorAt: String?,
)

private fun ContactWireDto.toDomain() =
    Contact(
        customerId = customerId,
        phone = phone,
        masked = masked,
        displayName = displayName,
        noShowCount = noShowCount,
        phoneVerifiedAt = phoneVerifiedAt,
        phoneConfirmedByOperatorAt = phoneConfirmedByOperatorAt,
    )
