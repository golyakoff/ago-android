package ago.chat.android.core.network.workerslots

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.workerslots.WorkerSlot
import ago.chat.android.core.domain.workerslots.WorkerSlotStatus
import ago.chat.android.core.domain.workerslots.WorkerSlotsApi
import ago.chat.android.core.domain.workerslots.WorkerSlotsResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `26-168`: the adapter behind [WorkerSlotsApi] — the same "the whole status-code-to-meaning mapping
 * lives here, and only here" shape [ago.chat.android.core.network.workers.KtorWorkersApi] already
 * establishes, and the same check-[calendarApiBaseUrl]-first, classify-never-invent discipline.
 *
 * **A single read, no writes** — [WorkerSlotsApi]'s own class doc comment states why this is a read-only
 * port, so unlike [ago.chat.android.core.network.workers.KtorWorkersApi] there is no shared `write`
 * helper here at all.
 *
 * **Never a raw exception class name or a hostname on screen** — [classify] answers only "no network" or
 * "something else", by exception type, never by printing `this::class.simpleName` or the exception's own
 * `message` (`UnknownHostException`'s message quotes the host it failed to resolve).
 */
public class KtorWorkerSlotsApi(
    private val client: HttpClient,
    private val calendarApiBaseUrl: String?,
) : WorkerSlotsApi {
    /**
     * `GET /api/v1/console/workers/{workerId}/slots?from&to` — [WorkerSlotsApi.fetchSlots]'s own doc
     * comment states the range is inclusive both ends. `worker_slots.invalid_range` is the one refusal
     * this read produces, and it already carries a genuine, caller-actionable `detail`
     * ([WorkerSlotsApi]'s own class doc comment on why no `type` check is needed here).
     */
    override suspend fun fetchSlots(
        workerId: String,
        from: String,
        to: String,
    ): WorkerSlotsResult {
        require(workerId.isNotBlank()) { "A worker id is never blank." }
        val baseUrl = calendarApiBaseUrl ?: return WorkerSlotsResult.NotConfigured

        val response =
            try {
                client.get("$baseUrl/api/v1/console/workers/$workerId/slots") {
                    parameter("from", from)
                    parameter("to", to)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return WorkerSlotsResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            val detail =
                try {
                    response.body<ProblemDetailsWireDto>().detail
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    null
                }
            return detail?.let { WorkerSlotsResult.Refused(it) }
                ?: WorkerSlotsResult.Failed(BookingsQueueFailure.Unexpected)
        }

        return try {
            WorkerSlotsResult.Loaded(response.body<List<WorkerSlotWireDto>>().map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "no slots" - the identical
            // `KtorWorkersApi.fetchWorkers`/`shapeGuard.ts` lesson, read onto this endpoint.
            WorkerSlotsResult.Failed(classify(failure))
        }
    }
}

/** `Ago.Calendar.Contracts.WorkerSlotResponse`, field for field. */
@Serializable
private data class WorkerSlotWireDto(
    val eventId: String,
    val localDate: String,
    val weekday: Int,
    val startsAt: String,
    val endsAt: String,
    val status: String,
    val serviceId: String? = null,
    val serviceName: String? = null,
    val personId: String? = null,
    val phone: String? = null,
    val masked: Boolean = false,
    val bookingId: String? = null,
)

private fun WorkerSlotWireDto.toDomain() =
    WorkerSlot(
        eventId = eventId,
        localDate = localDate,
        weekday = weekday,
        startsAt = startsAt,
        endsAt = endsAt,
        status = status.toWorkerSlotStatus(),
        rawStatus = status,
        serviceId = serviceId,
        serviceName = serviceName,
        personId = personId,
        phone = phone,
        masked = masked,
        bookingId = bookingId,
    )

/** [WorkerSlotStatus]'s own doc comment states why an unrecognised spelling becomes [WorkerSlotStatus.Unknown]
 * rather than a parse failure - the defensive stance
 * [ago.chat.android.core.network.readiness.KtorBookingReadinessApi]'s own `toBookingPrecondition` already
 * takes for a wire enum this app does not fully control the growth of. */
private fun String.toWorkerSlotStatus(): WorkerSlotStatus =
    when (this) {
        "Available" -> WorkerSlotStatus.Available
        "PendingConfirmation" -> WorkerSlotStatus.PendingConfirmation
        "Booked" -> WorkerSlotStatus.Booked
        "Cancelled" -> WorkerSlotStatus.Cancelled
        "NoShow" -> WorkerSlotStatus.NoShow
        "Blocked" -> WorkerSlotStatus.Blocked
        else -> WorkerSlotStatus.Unknown
    }

/** RFC 7807, read for exactly the one field this call needs — the identical, deliberately un-shared copy
 * [ago.chat.android.core.network.workers.KtorWorkersApi]'s own private `ProblemDetailsWireDto` already
 * establishes. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/** See `KtorWorkersApi`'s own `classify` for why this exists instead of a `describe()` copy. */
private fun classify(failure: Exception): BookingsQueueFailure =
    if (failure is IOException) BookingsQueueFailure.Transport else BookingsQueueFailure.Unexpected
