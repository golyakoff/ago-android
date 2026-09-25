package ago.chat.android.core.network.workers

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfiguredService
import ago.chat.android.core.domain.workers.Worker
import ago.chat.android.core.domain.workers.WorkerCalendar
import ago.chat.android.core.domain.workers.WorkerDetailResult
import ago.chat.android.core.domain.workers.WorkerDraft
import ago.chat.android.core.domain.workers.WorkersApi
import ago.chat.android.core.domain.workers.WorkersResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
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
 * `26-139`: the adapter behind [WorkersApi] — the same "the whole status-code-to-meaning mapping lives
 * here, and only here" shape [ago.chat.android.core.network.bookings.KtorBookingsApi] and
 * [ago.chat.android.core.network.schedule.KtorWorkingHoursApi] already state, and the same
 * check-[calendarApiBaseUrl]-first, classify-never-invent discipline.
 *
 * **[calendarApiBaseUrl] is checked first, before any network call.** A `null` base URL means this
 * deployment does not run AGO Calendar at all — the reads answer `NotConfigured` immediately with zero
 * requests made, and the writes answer [BookingsQueueFailure.Unexpected] (they are only reachable from
 * a screen that already got a `Loaded` against a non-null base URL — the identical unreachable-case
 * reasoning `KtorBookingsApi.performBookingAction` records).
 *
 * **Never a raw exception class name or a hostname on screen** — [classify] answers only "no network"
 * or "something else", by exception type, never by printing `this::class.simpleName` or the
 * exception's own `message` (`UnknownHostException`'s message quotes the host it failed to resolve).
 */
public class KtorWorkersApi(
    private val client: HttpClient,
    private val calendarApiBaseUrl: String?,
) : WorkersApi {
    /**
     * `GET /api/v1/console/workers` for the roster, then `GET /api/v1/console/configuration` for the
     * calendar each worker belongs to and the picker/checkbox source lists — the two reads
     * [WorkersApi.fetchWorkers]'s own doc comment explains must be stitched together, because the
     * worker response carries no calendar membership of its own.
     *
     * Both reads must land: a failure or a non-2xx on either is the whole read's failure, never a
     * half-populated screen. The calendar for each worker is resolved from `calendars[].workerIds`; a
     * worker no calendar lists keeps a `null` calendar rather than vanishing (the identical
     * "a row nobody can see is a row nobody can correct" resilience `KtorWorkingHoursApi.toRules`
     * establishes).
     */
    override suspend fun fetchWorkers(): WorkersResult {
        val baseUrl = calendarApiBaseUrl ?: return WorkersResult.NotConfigured

        val workersResponse =
            try {
                client.get("$baseUrl/api/v1/console/workers")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return WorkersResult.Failed(classify(failure))
            }
        if (!workersResponse.status.isSuccess()) {
            return WorkersResult.Failed(BookingsQueueFailure.Unexpected)
        }

        val configurationResponse =
            try {
                client.get("$baseUrl/api/v1/console/configuration")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return WorkersResult.Failed(classify(failure))
            }
        if (!configurationResponse.status.isSuccess()) {
            return WorkersResult.Failed(BookingsQueueFailure.Unexpected)
        }

        return try {
            val roster = workersResponse.body<List<WorkerWireDto>>()
            val configuration = configurationResponse.body<TenantConfigurationWireDto>()
            val calendarIdByWorkerId =
                configuration.calendars
                    .flatMap { calendar -> calendar.workerIds.map { workerId -> workerId to calendar.calendarId } }
                    .toMap()
            WorkersResult.Loaded(
                workers = roster.map { it.toDomain(calendarIdByWorkerId[it.workerId]) },
                calendars = configuration.calendars.map { WorkerCalendar(it.calendarId, it.name) },
                services = configuration.services.map { it.toDomain() },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "no workers" - the identical
            // `KtorBookingsApi.fetchPendingQueue`/`shapeGuard.ts` lesson, read onto this endpoint.
            WorkersResult.Failed(classify(failure))
        }
    }

    /**
     * `GET /api/v1/console/workers/{workerId}` — one worker, with a `null` calendar for the reason
     * [WorkersApi.fetchWorker]'s own doc comment gives: a single `WorkerResponse` carries no calendar
     * membership, and this read does not make a second request to `configuration` for it.
     */
    override suspend fun fetchWorker(workerId: String): WorkerDetailResult {
        require(workerId.isNotBlank()) { "A worker id is never blank." }
        val baseUrl = calendarApiBaseUrl ?: return WorkerDetailResult.NotConfigured

        val response =
            try {
                client.get("$baseUrl/api/v1/console/workers/$workerId")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return WorkerDetailResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            return WorkerDetailResult.Failed(BookingsQueueFailure.Unexpected)
        }

        return try {
            WorkerDetailResult.Loaded(response.body<WorkerWireDto>().toDomain(calendarId = null))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            WorkerDetailResult.Failed(classify(failure))
        }
    }

    /**
     * `POST /api/v1/console/workers` — [WorkerDraft.calendarId] is sent, [WorkerDraft.isActive] is not
     * ([WorkersApi.createWorker]'s own doc comment says why). `201 Created` is [BookingActionResult.Succeeded];
     * the created id in the body is not read, because the caller re-reads the roster rather than trusting
     * the echo. [contentType]/[setBody] are required for the reason `KtorBookingsApi.revealCustomerPhone`'s
     * own doc comment gives: Ktor's `ContentNegotiation` only serializes a body it can match against a
     * declared `Content-Type`.
     */
    override suspend fun createWorker(draft: WorkerDraft): BookingActionResult =
        write {
            client.post("$it/api/v1/console/workers") {
                contentType(ContentType.Application.Json)
                setBody(
                    CreateWorkerRequestWireDto(
                        lastName = draft.lastName,
                        firstName = draft.firstName,
                        middleName = draft.middleName,
                        displayName = draft.displayName,
                        calendarId = draft.calendarId,
                        serviceIds = draft.serviceIds,
                    ),
                )
            }
        }

    /**
     * `PUT /api/v1/console/workers/{workerId}` — [WorkerDraft.isActive] is sent, [WorkerDraft.calendarId]
     * is not ([WorkersApi.updateWorker]'s own doc comment says why). Replace semantics: every field
     * travels every time. `204` is [BookingActionResult.Succeeded].
     */
    override suspend fun updateWorker(
        workerId: String,
        draft: WorkerDraft,
    ): BookingActionResult {
        require(workerId.isNotBlank()) { "A worker id is never blank." }
        return write {
            client.put("$it/api/v1/console/workers/$workerId") {
                contentType(ContentType.Application.Json)
                setBody(
                    UpdateWorkerRequestWireDto(
                        lastName = draft.lastName,
                        firstName = draft.firstName,
                        middleName = draft.middleName,
                        displayName = draft.displayName,
                        isActive = draft.isActive,
                        serviceIds = draft.serviceIds,
                    ),
                )
            }
        }
    }

    /**
     * `DELETE /api/v1/console/workers/{workerId}` — `204` is [BookingActionResult.Succeeded]; a `409`
     * whose RFC 7807 `detail` says the worker was booked and must be deactivated instead comes back as
     * [BookingActionResult.Refused] and is shown verbatim ([WorkersApi.deleteWorker]'s own doc comment
     * on why the refusal is the point of the call).
     */
    override suspend fun deleteWorker(workerId: String): BookingActionResult {
        require(workerId.isNotBlank()) { "A worker id is never blank." }
        return write { client.delete("$it/api/v1/console/workers/$workerId") }
    }

    /**
     * The one place the three writes' shared shape lives — they differ only in verb and body. A `2xx`
     * (a `201` from create, a `204` from update and delete alike) is [BookingActionResult.Succeeded]; a
     * non-2xx is read for an RFC 7807 `detail`, a genuine one becoming [BookingActionResult.Refused]
     * (shown verbatim) and everything else [BookingActionResult.Failed] — the identical shape
     * `KtorBookingsApi.performBookingAction` establishes, restated here because these writes carry a
     * body and a different path shape.
     *
     * A `null` [calendarApiBaseUrl] here is unreachable in practice — the screen that calls these only
     * has a row on it once [fetchWorkers] returned [WorkersResult.Loaded] against the same non-null base
     * URL — so it is [BookingsQueueFailure.Unexpected] rather than an arm invented for a case with no
     * way to occur.
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

        return detail?.let { BookingActionResult.Refused(it) } ?: BookingActionResult.Failed(BookingsQueueFailure.Unexpected)
    }
}

/** `Ago.Calendar.Contracts.WorkerResponse`, reduced to the fields [Worker] carries -
 * `displayNameIsCustom`/`createdAt`/`updatedAt` are on the wire and simply omitted, the identical
 * `ignoreUnknownKeys` (`AgoHttpClient.kt`'s own `agoJson`) reduction `KtorBookingsApi`'s own wire DTOs
 * rely on. `serviceIds` are `Guid`s server-side, carried as their string form on the wire. */
@Serializable
private data class WorkerWireDto(
    val workerId: String,
    val lastName: String,
    val firstName: String,
    val middleName: String? = null,
    val displayName: String,
    val isActive: Boolean,
    val serviceIds: List<String> = emptyList(),
)

private fun WorkerWireDto.toDomain(calendarId: String?) =
    Worker(
        workerId = workerId,
        lastName = lastName,
        firstName = firstName,
        middleName = middleName,
        displayName = displayName,
        isActive = isActive,
        serviceIds = serviceIds,
        calendarId = calendarId,
    )

/** `Ago.Calendar.Contracts.CreateWorkerRequest` - no `isActive` (a new worker is always active). */
@Serializable
private data class CreateWorkerRequestWireDto(
    val lastName: String,
    val firstName: String,
    val middleName: String?,
    val displayName: String?,
    val calendarId: String,
    val serviceIds: List<String>,
)

/** `Ago.Calendar.Contracts.UpdateWorkerRequest` - no `calendarId` (a worker is never moved), and
 * `isActive` (deactivation is how a booked worker leaves rotation). */
@Serializable
private data class UpdateWorkerRequestWireDto(
    val lastName: String,
    val firstName: String,
    val middleName: String?,
    val displayName: String?,
    val isActive: Boolean,
    val serviceIds: List<String>,
)

/** `Ago.Calendar.Contracts.TenantConfigurationResponse`, reduced to the two collections a worker card
 * and its edit form need - `ignoreUnknownKeys` is what lets this omit the origins, the public key, the
 * workers array (the roster comes from `GET /workers` in full) and the quota. */
@Serializable
private data class TenantConfigurationWireDto(
    val calendars: List<ConfiguredCalendarWireDto> = emptyList(),
    val services: List<ConfiguredServiceWireDto> = emptyList(),
)

/** `Ago.Calendar.Contracts.ConfiguredCalendarResponse`, reduced to the picker's id and label plus the
 * `workerIds` that resolve each worker's calendar - `timeZone`/`isPublished`/`workingHours` are omitted. */
@Serializable
private data class ConfiguredCalendarWireDto(
    val calendarId: String,
    val name: String,
    val workerIds: List<String> = emptyList(),
)

/** `Ago.Calendar.Contracts.ConfiguredServiceResponse`, field for field - the identical, deliberately
 * un-shared copy `KtorBookingsApi`'s own private `ConfiguredServiceWireDto` establishes, restated here
 * rather than imported across adapters for the same reason that copy is not shared. `isActive` carries
 * no default: a response that lacked it would be a server this app does not understand, and defaulting
 * it to `true` would render a withdrawn service as though still on offer. */
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

/** RFC 7807, read for exactly the one field a refusal needs - the identical, deliberately un-shared
 * copy `KtorBookingsApi`'s own private `ProblemDetailsWireDto` establishes. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/** See `KtorBookingsApi`'s own `classify` for why this exists instead of a `describe()` copy. */
private fun classify(failure: Exception): BookingsQueueFailure =
    if (failure is IOException) BookingsQueueFailure.Transport else BookingsQueueFailure.Unexpected
