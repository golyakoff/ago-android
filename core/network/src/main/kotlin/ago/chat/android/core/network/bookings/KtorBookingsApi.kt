package ago.chat.android.core.network.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfiguredService
import ago.chat.android.core.domain.bookings.ConfirmedBooking
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.core.domain.bookings.ContactsResult
import ago.chat.android.core.domain.bookings.PendingBooking
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.bookings.ServicesResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
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

    /** `26-49`: `POST /api/v1/console/bookings/{bookingId}/reject`, the first of three identically-shaped
     * veto writes — see [performBookingAction] for the one place their shared shape actually lives. */
    override suspend fun rejectBooking(bookingId: String): BookingActionResult = performBookingAction(bookingId, "reject")

    /** `26-49`: `POST /api/v1/console/bookings/{bookingId}/cancel` — [performBookingAction]'s own doc
     * comment covers this and [markNoShow] too. */
    override suspend fun cancelBooking(bookingId: String): BookingActionResult = performBookingAction(bookingId, "cancel")

    /** `26-49`: `POST /api/v1/console/bookings/{bookingId}/no-show` — [performBookingAction]'s own doc
     * comment covers this and [cancelBooking] too. */
    override suspend fun markNoShow(bookingId: String): BookingActionResult = performBookingAction(bookingId, "no-show")

    /**
     * `26-49`: the one place `POST /api/v1/console/bookings/{bookingId}/{verb}`'s shared shape lives —
     * [rejectBooking]/[cancelBooking]/[markNoShow] differ only in which literal path segment they send,
     * an argument rather than three near-identical method bodies. `204` is [BookingActionResult.Succeeded];
     * a non-2xx is read for an RFC 7807 `detail` the identical way [fetchPendingQueue]'s own sibling
     * write on the conversation queue does
     * (`ago.chat.android.core.network.conversations.KtorConversationsApi.claim`'s own doc comment) —
     * a genuine `detail` is [BookingActionResult.Refused], shown verbatim; anything else, [Failed].
     *
     * A `null` [calendarApiBaseUrl] here would mean this method was called for a deployment that does
     * not run AGO Calendar at all — unreachable in practice, since the screen that calls this only ever
     * has a row on it once [fetchPendingQueue] itself has already returned [PendingBookingsResult.Loaded]
     * against that same non-null base URL. [BookingActionResult] has no `NotConfigured` arm of its own
     * (unlike the three reads above) precisely because this state is not a real one for a write to
     * reach; [BookingsQueueFailure.Unexpected] is the honest "this should not be happening" answer if it
     * somehow did, not a fourth arm invented for a case with no way to occur.
     */
    private suspend fun performBookingAction(
        bookingId: String,
        verb: String,
    ): BookingActionResult {
        val baseUrl = calendarApiBaseUrl ?: return BookingActionResult.Failed(BookingsQueueFailure.Unexpected)

        val response =
            try {
                client.post("$baseUrl/api/v1/console/bookings/$bookingId/$verb")
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

        return detail?.let { BookingActionResult.Refused(it) } ?: BookingActionResult.Failed(BookingsQueueFailure.Unexpected)
    }

    /**
     * `26-53`: `POST /api/v1/console/contacts/{customerId}/reveal-phone`, body `{"surface": surface}` —
     * the identical `204`-or-refusal shape [performBookingAction] establishes, with one difference: a
     * success here carries a body (`CustomerPhoneRevealResponse.Phone`), so this method is not folded
     * into that one shared helper. [contentType]/[setBody] are required for the exact reason
     * `KtorConversationsApi.markRead`'s own doc comment gives for its own first request body: Ktor's
     * `ContentNegotiation` only serializes a body it can match against a declared `Content-Type`.
     */
    override suspend fun revealCustomerPhone(
        customerId: String,
        surface: String,
    ): RevealPhoneResult {
        val baseUrl = calendarApiBaseUrl ?: return RevealPhoneResult.Failed(BookingsQueueFailure.Unexpected)

        val response =
            try {
                client.post("$baseUrl/api/v1/console/contacts/$customerId/reveal-phone") {
                    contentType(ContentType.Application.Json)
                    setBody(RevealCustomerPhoneRequestWireDto(surface))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return RevealPhoneResult.Failed(classify(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                RevealPhoneResult.Revealed(response.body<RevealPhoneResponseWireDto>().phone)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // A `2xx` whose body is not the promised shape is not a revealed number - the identical
                // `fetchPendingQueue`/`shapeGuard.ts` lesson, read onto this endpoint.
                RevealPhoneResult.Failed(classify(failure))
            }
        }

        val detail =
            try {
                response.body<ProblemDetailsWireDto>().detail
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }

        return detail?.let { RevealPhoneResult.Refused(it) } ?: RevealPhoneResult.Failed(BookingsQueueFailure.Unexpected)
    }

    /**
     * `26-96`: `GET /api/v1/console/configuration`, projected down to its `services` array — the
     * identical check-base-URL-first, classify-never-invent shape every read above establishes.
     *
     * [TenantConfigurationWireDto] declares exactly one of that response's seven fields. Everything
     * else (`calendars`, `workers`, `allowedOrigins`, `publicKey`, `tenantName`, `workerQuota`) is
     * simply omitted rather than declared and discarded — `agoJson`'s own `ignoreUnknownKeys`
     * (`AgoHttpClient.kt`) is what makes that safe, the identical reason [PendingBookingWireDto] omits
     * the fields its own screen has no use for.
     */
    override suspend fun fetchServices(): ServicesResult {
        val baseUrl = calendarApiBaseUrl ?: return ServicesResult.NotConfigured

        val response =
            try {
                client.get("$baseUrl/api/v1/console/configuration")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ServicesResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            return ServicesResult.Failed(BookingsQueueFailure.Unexpected)
        }

        return try {
            ServicesResult.Loaded(response.body<TenantConfigurationWireDto>().services.map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ServicesResult.Failed(classify(failure))
        }
    }

    /**
     * `26-96`: `PUT /api/v1/console/services/{serviceId}` — the identical `204`-or-refusal shape
     * [performBookingAction] establishes, not folded into it because this one carries a body and takes
     * a different path shape. [contentType]/[setBody] are required for the reason
     * [revealCustomerPhone]'s own doc comment gives.
     *
     * Every field travels on every call, including the ones the caller did not change: the endpoint has
     * replace semantics, and omitting a field would clear it rather than leave it alone
     * (`Ago.Calendar.Contracts.UpdateServiceRequest`'s own remarks).
     */
    override suspend fun updateService(
        serviceId: String,
        name: String,
        durationMinutes: Int,
        priceMinorUnits: Int?,
        priceIsFrom: Boolean,
        description: String?,
        isActive: Boolean,
    ): BookingActionResult {
        val baseUrl = calendarApiBaseUrl ?: return BookingActionResult.Failed(BookingsQueueFailure.Unexpected)

        val response =
            try {
                client.put("$baseUrl/api/v1/console/services/$serviceId") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        UpdateServiceRequestWireDto(
                            name = name,
                            durationMinutes = durationMinutes,
                            priceMinorUnits = priceMinorUnits,
                            priceIsFrom = priceIsFrom,
                            description = description,
                            isActive = isActive,
                        ),
                    )
                }
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

        return detail?.let { BookingActionResult.Refused(it) } ?: BookingActionResult.Failed(BookingsQueueFailure.Unexpected)
    }
}

/** `Ago.Calendar.Contracts.TenantConfigurationResponse`, reduced to the one field this app reads —
 * see [KtorBookingsApi.fetchServices]'s own doc comment for why the other six are omitted rather than
 * declared. */
@Serializable
private data class TenantConfigurationWireDto(
    val services: List<ConfiguredServiceWireDto> = emptyList(),
)

/** `Ago.Calendar.Contracts.ConfiguredServiceResponse`, field for field. `isActive` carries no default:
 * a response that somehow lacked it would be a server this app does not understand, and defaulting it
 * to `true` would silently render every withdrawn service as though it were still on offer. */
@Serializable
private data class ConfiguredServiceWireDto(
    val serviceId: String,
    val name: String,
    val durationMinutes: Int,
    val priceMinorUnits: Int? = null,
    val priceCurrencyCode: String? = null,
    val priceIsFrom: Boolean = false,
    val description: String? = null,
    val isActive: Boolean,
)

private fun ConfiguredServiceWireDto.toDomain() =
    ConfiguredService(
        serviceId = serviceId,
        name = name,
        durationMinutes = durationMinutes,
        priceMinorUnits = priceMinorUnits,
        priceCurrencyCode = priceCurrencyCode,
        priceIsFrom = priceIsFrom,
        description = description,
        isActive = isActive,
    )

/** `Ago.Calendar.Contracts.UpdateServiceRequest` - all six fields, every time
 * ([KtorBookingsApi.updateService]'s own doc comment on replace semantics). */
@Serializable
private data class UpdateServiceRequestWireDto(
    val name: String,
    val durationMinutes: Int,
    val priceMinorUnits: Int?,
    val priceIsFrom: Boolean,
    val description: String?,
    val isActive: Boolean,
)

/** `Ago.Calendar.Contracts.RevealCustomerPhoneRequest` - the one field that endpoint's own body carries. */
@Serializable
private data class RevealCustomerPhoneRequestWireDto(
    val surface: String,
)

/** `Ago.Calendar.Contracts.CustomerPhoneRevealResponse` - the one field a successful reveal's own body
 * carries. */
@Serializable
private data class RevealPhoneResponseWireDto(
    val phone: String,
)

/** RFC 7807, read for exactly the one field a booking-action refusal needs — the identical, deliberately
 * un-shared copy [ago.chat.android.core.network.conversations.KtorConversationsApi]'s own private
 * `ProblemDetailsWireDto` already establishes for the conversation queue's own claim refusal, restated
 * here rather than imported across adapters for the same reason that copy is not shared with this one. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

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
