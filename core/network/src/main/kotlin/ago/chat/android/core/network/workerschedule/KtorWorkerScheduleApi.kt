package ago.chat.android.core.network.workerschedule

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.workerschedule.SaveWorkerScheduleResult
import ago.chat.android.core.domain.workerschedule.ScheduleKind
import ago.chat.android.core.domain.workerschedule.WorkerSchedule
import ago.chat.android.core.domain.workerschedule.WorkerScheduleApi
import ago.chat.android.core.domain.workerschedule.WorkerScheduleDraft
import ago.chat.android.core.domain.workerschedule.WorkerScheduleResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `26-168`: the adapter behind [WorkerScheduleApi] — the same "the whole status-code-to-meaning mapping
 * lives here, and only here" shape [ago.chat.android.core.network.workers.KtorWorkersApi] and
 * [ago.chat.android.core.network.schedule.KtorWorkingHoursApi] already establish, and the same
 * check-[calendarApiBaseUrl]-first, classify-never-invent discipline.
 *
 * **`type`, not just `detail`, on the read.** [fetchSchedule]'s own doc comment on
 * [WorkerScheduleApi] explains why `configuration.no_schedule` must be told apart from a genuine failure
 * — [NO_SCHEDULE_PROBLEM_TYPE] is read the identical way
 * [ago.chat.android.core.network.analytics.KtorSiteAnalyticsApi] already reads `Analytics.InvalidRange`,
 * a stable machine code, never the human `detail` sentence.
 *
 * **Never a raw exception class name or a hostname on screen** — [classify] answers only "no network" or
 * "something else", by exception type, never by printing `this::class.simpleName` or the exception's own
 * `message` (`UnknownHostException`'s message quotes the host it failed to resolve).
 */
public class KtorWorkerScheduleApi(
    private val client: HttpClient,
    private val calendarApiBaseUrl: String?,
) : WorkerScheduleApi {
    override suspend fun fetchSchedule(workerId: String): WorkerScheduleResult {
        require(workerId.isNotBlank()) { "A worker id is never blank." }
        val baseUrl = calendarApiBaseUrl ?: return WorkerScheduleResult.NotConfigured

        val response =
            try {
                client.get("$baseUrl/api/v1/console/workers/$workerId/schedule")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return WorkerScheduleResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            val type =
                try {
                    response.body<ProblemDetailsWireDto>().type
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    null
                }
            return if (type == NO_SCHEDULE_PROBLEM_TYPE) {
                WorkerScheduleResult.None
            } else {
                WorkerScheduleResult.Failed(BookingsQueueFailure.Unexpected)
            }
        }

        return try {
            WorkerScheduleResult.Loaded(response.body<WorkerScheduleWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "no schedule" - that is
            // `configuration.no_schedule` above, and only above. This is a genuinely different problem.
            WorkerScheduleResult.Failed(classify(failure))
        }
    }

    /**
     * `PUT /api/v1/console/workers/{workerId}/schedule` — create-or-replace, [WorkerScheduleApi.saveSchedule]'s
     * own doc comment states why every field is sent every time. A `2xx` body is read back into
     * [SaveWorkerScheduleResult.Saved] directly, never re-fetched, so the caller sees the cursor and
     * horizon the server actually stored in the same round trip as the save.
     */
    override suspend fun saveSchedule(
        workerId: String,
        draft: WorkerScheduleDraft,
    ): SaveWorkerScheduleResult {
        require(workerId.isNotBlank()) { "A worker id is never blank." }
        val baseUrl = calendarApiBaseUrl ?: return SaveWorkerScheduleResult.Failed(BookingsQueueFailure.Unexpected)

        val response =
            try {
                client.put("$baseUrl/api/v1/console/workers/$workerId/schedule") {
                    contentType(ContentType.Application.Json)
                    setBody(draft.toWireDto())
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return SaveWorkerScheduleResult.Failed(classify(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                SaveWorkerScheduleResult.Saved(response.body<WorkerScheduleWireDto>().toDomain())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                SaveWorkerScheduleResult.Failed(classify(failure))
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

        return detail?.let { SaveWorkerScheduleResult.Refused(it) }
            ?: SaveWorkerScheduleResult.Failed(BookingsQueueFailure.Unexpected)
    }
}

/** `Ago.Calendar.Application.UseCases.Configuration.ConfigurationErrors.NoSchedule`'s own RFC 7807
 * `type` — named rather than inlined so this adapter and its own test cannot drift on the spelling. */
private const val NO_SCHEDULE_PROBLEM_TYPE = "configuration.no_schedule"

/** `Ago.Calendar.Contracts.WorkerScheduleResponse`, field for field. */
@Serializable
private data class WorkerScheduleWireDto(
    val scheduleId: String,
    val workerId: String,
    val kind: String,
    val cycleAnchor: String? = null,
    val cycleWorkingDays: Int? = null,
    val cycleRestDays: Int? = null,
    val cycleStartsAt: String? = null,
    val cycleEndsAt: String? = null,
    val slotMinutes: Int,
    val bufferMinutes: Int,
    val horizonDays: Int,
    val materializeFrom: String,
    val createdAt: String,
    val updatedAt: String,
    val buffersCountTowardServiceDuration: Boolean,
)

private fun WorkerScheduleWireDto.toDomain() =
    WorkerSchedule(
        scheduleId = scheduleId,
        workerId = workerId,
        kind = kind.toScheduleKind(),
        cycleAnchor = cycleAnchor,
        cycleWorkingDays = cycleWorkingDays,
        cycleRestDays = cycleRestDays,
        cycleStartsAt = cycleStartsAt,
        cycleEndsAt = cycleEndsAt,
        slotMinutes = slotMinutes,
        bufferMinutes = bufferMinutes,
        horizonDays = horizonDays,
        materializeFrom = materializeFrom,
        createdAt = createdAt,
        updatedAt = updatedAt,
        buffersCountTowardServiceDuration = buffersCountTowardServiceDuration,
    )

/** [ScheduleKind]'s own doc comment states why an unrecognised spelling is a thrown shape mismatch
 * (caught by [fetchSchedule]/[saveSchedule]'s own `catch (failure: Exception)`) rather than a defensive
 * third case: the server itself refuses any third spelling before a schedule can ever carry one. */
private fun String.toScheduleKind(): ScheduleKind =
    when (this) {
        "Weekly" -> ScheduleKind.Weekly
        "Cycle" -> ScheduleKind.Cycle
        else -> error("Unknown schedule kind '$this'.")
    }

/**
 * `Ago.Calendar.Contracts.SaveWorkerScheduleRequest`, field for field. Deliberately **no default
 * values** on the nullable cycle fields, unlike this file's own [WorkerScheduleWireDto] (a response,
 * only ever deserialized) — a default here would risk `agoJson` omitting an explicit `null` from the
 * outgoing body if this app's serializer config ever turns `encodeDefaults` off, and
 * [WorkerScheduleApi.saveSchedule]'s own replace semantics require every field to always be sent, the
 * identical reasoning every other outgoing wire DTO in this app (`CreateWorkerRequestWireDto`,
 * `UpdateWorkingHoursRuleWireDto`) already follows by declaring no defaults at all.
 */
@Serializable
private data class SaveWorkerScheduleWireDto(
    val kind: String,
    val cycleAnchor: String?,
    val cycleWorkingDays: Int?,
    val cycleRestDays: Int?,
    val cycleStartsAt: String?,
    val cycleEndsAt: String?,
    val slotMinutes: Int,
    val bufferMinutes: Int,
    val horizonDays: Int,
    val materializeFrom: String,
    val buffersCountTowardServiceDuration: Boolean,
)

private fun WorkerScheduleDraft.toWireDto() =
    SaveWorkerScheduleWireDto(
        kind =
            when (kind) {
                ScheduleKind.Weekly -> "Weekly"
                ScheduleKind.Cycle -> "Cycle"
            },
        cycleAnchor = cycleAnchor,
        cycleWorkingDays = cycleWorkingDays,
        cycleRestDays = cycleRestDays,
        cycleStartsAt = cycleStartsAt,
        cycleEndsAt = cycleEndsAt,
        slotMinutes = slotMinutes,
        bufferMinutes = bufferMinutes,
        horizonDays = horizonDays,
        materializeFrom = materializeFrom,
        buffersCountTowardServiceDuration = buffersCountTowardServiceDuration,
    )

/** RFC 7807, read for the two fields this adapter needs — `detail` for a genuine write refusal, `type`
 * for [fetchSchedule]'s own `configuration.no_schedule` branch. A second, deliberately un-shared
 * declaration from every sibling adapter's own private copy — [ago.chat.android.core.network.workers.KtorWorkersApi]'s
 * own private `ProblemDetailsWireDto` states why. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
    val type: String? = null,
)

/** See `KtorWorkersApi`'s own `classify` for why this exists instead of a `describe()` copy. */
private fun classify(failure: Exception): BookingsQueueFailure =
    if (failure is IOException) BookingsQueueFailure.Transport else BookingsQueueFailure.Unexpected
