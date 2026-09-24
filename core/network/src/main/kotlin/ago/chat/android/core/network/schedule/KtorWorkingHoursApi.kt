package ago.chat.android.core.network.schedule

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.schedule.WorkingHoursApi
import ago.chat.android.core.domain.schedule.WorkingHoursChangeResult
import ago.chat.android.core.domain.schedule.WorkingHoursReconciliation
import ago.chat.android.core.domain.schedule.WorkingHoursResult
import ago.chat.android.core.domain.schedule.WorkingHoursRule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
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
 * `26-97`: the adapter behind [WorkingHoursApi] — the same "the whole status-code-to-meaning mapping
 * lives here, and only here" shape
 * [ago.chat.android.core.network.bookings.KtorBookingsApi]'s own doc comment states, and the same
 * check-[calendarApiBaseUrl]-first, classify-never-invent discipline.
 *
 * **Never a raw exception class name or a hostname on screen** — [classify] answers only "no network"
 * or "something else", by exception type, never by printing `this::class.simpleName` or the
 * exception's own `message` (`UnknownHostException`'s message quotes the host it failed to resolve).
 */
public class KtorWorkingHoursApi(
    private val client: HttpClient,
    private val calendarApiBaseUrl: String?,
) : WorkingHoursApi {
    override suspend fun fetchWorkingHours(): WorkingHoursResult {
        val baseUrl = calendarApiBaseUrl ?: return WorkingHoursResult.NotConfigured

        val response =
            try {
                client.get("$baseUrl/api/v1/console/configuration")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return WorkingHoursResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            return WorkingHoursResult.Failed(BookingsQueueFailure.Unexpected)
        }

        return try {
            WorkingHoursResult.Loaded(response.body<TenantConfigurationWireDto>().toRules())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "no working hours" - the identical
            // lesson `KtorBookingsApi.fetchPendingQueue` records for its own empty-looking failure.
            WorkingHoursResult.Failed(classify(failure))
        }
    }

    override suspend fun updateWorkingHoursRule(
        ruleId: String,
        dayOfWeek: Int,
        startsAt: String,
        endsAt: String,
    ): WorkingHoursChangeResult =
        change(ruleId) {
            client.put("$it/api/v1/console/working-hours/$ruleId") {
                // Required for the reason `KtorConversationsApi.markRead`'s own doc comment gives:
                // Ktor's ContentNegotiation only serializes a body it can match against a declared
                // Content-Type.
                contentType(ContentType.Application.Json)
                setBody(UpdateWorkingHoursRuleWireDto(dayOfWeek, startsAt, endsAt))
            }
        }

    override suspend fun deleteWorkingHoursRule(ruleId: String): WorkingHoursChangeResult =
        change(ruleId) { client.delete("$it/api/v1/console/working-hours/$ruleId") }

    /**
     * The one place the two writes' shared shape lives — they differ only in verb and body. Both answer
     * `200` with a [WorkingHoursRuleChangeWireDto], and a `2xx` whose body is not that shape is *not*
     * treated as a silent success: the reconciliation is the whole point of the call, so a response
     * this adapter cannot read is a failure, not a change with nothing to report.
     *
     * A `null` [calendarApiBaseUrl] here is unreachable in practice - the screen that calls this only
     * has a row on it once [fetchWorkingHours] returned [WorkingHoursResult.Loaded] against the same
     * non-null base URL - so it is [BookingsQueueFailure.Unexpected] rather than a fourth arm invented
     * for a case with no way to occur, the identical reasoning `KtorBookingsApi.performBookingAction`
     * records.
     */
    private suspend fun change(
        ruleId: String,
        request: suspend (String) -> io.ktor.client.statement.HttpResponse,
    ): WorkingHoursChangeResult {
        require(ruleId.isNotBlank()) { "A working-hours rule id is never blank." }
        val baseUrl = calendarApiBaseUrl ?: return WorkingHoursChangeResult.Failed(BookingsQueueFailure.Unexpected)

        val response =
            try {
                request(baseUrl)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return WorkingHoursChangeResult.Failed(classify(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                WorkingHoursChangeResult.Changed(response.body<WorkingHoursRuleChangeWireDto>().reconciliation.toDomain())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                WorkingHoursChangeResult.Failed(classify(failure))
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

        return detail?.let { WorkingHoursChangeResult.Refused(it) }
            ?: WorkingHoursChangeResult.Failed(BookingsQueueFailure.Unexpected)
    }
}

/** `Ago.Calendar.Contracts.UpdateWorkingHoursRuleRequest` - the three fields a human types, and no
 * calendar or worker id: a rule is corrected where it is, never moved. */
@Serializable
private data class UpdateWorkingHoursRuleWireDto(
    val dayOfWeek: Int,
    val startsAt: String,
    val endsAt: String,
)

/** `Ago.Calendar.Contracts.WorkingHoursRuleChangeResponse`. `rule` is deliberately not read: this app
 * re-reads the whole configuration after a successful write rather than patching one row from the
 * echo, so the only field worth carrying is the one the next `GET` cannot produce. */
@Serializable
private data class WorkingHoursRuleChangeWireDto(
    val reconciliation: WorkingHoursReconciliationWireDto,
)

@Serializable
private data class WorkingHoursReconciliationWireDto(
    val recutFrom: String? = null,
    val alreadyCutDays: List<String> = emptyList(),
    val liveBookingCount: Int = 0,
)

private fun WorkingHoursReconciliationWireDto.toDomain() =
    WorkingHoursReconciliation(
        recutFrom = recutFrom,
        alreadyCutDays = alreadyCutDays,
        liveBookingCount = liveBookingCount,
    )

/** `Ago.Calendar.Contracts.TenantConfigurationResponse`, reduced to the two collections a
 * working-hours row needs - `ignoreUnknownKeys` (`AgoHttpClient.kt`'s own `agoJson`) is what lets this
 * omit the origins, the services and the public key rather than declare and discard them. */
@Serializable
private data class TenantConfigurationWireDto(
    val calendars: List<ConfiguredCalendarWireDto> = emptyList(),
    val workers: List<ConfiguredWorkerWireDto> = emptyList(),
)

@Serializable
private data class ConfiguredCalendarWireDto(
    val calendarId: String,
    val name: String,
    val workingHours: List<WorkingHoursRuleWireDto> = emptyList(),
)

@Serializable
private data class ConfiguredWorkerWireDto(
    val workerId: String,
    val displayName: String,
)

@Serializable
private data class WorkingHoursRuleWireDto(
    val ruleId: String,
    val workerId: String,
    val dayOfWeek: Int,
    val startsAt: String,
    val endsAt: String,
)

/** Flattened across calendars, in the order the server already sorted them (worker, then weekday, then
 * opening time - `WorkingHoursRuleRepository.ListForCalendarAsync`), so this app imposes no ordering
 * of its own on a list the server already has an opinion about. A rule naming a worker the roster no
 * longer lists falls back to the id rather than being dropped: a row nobody can see is a row nobody
 * can correct, which is the defect this item fixes. */
private fun TenantConfigurationWireDto.toRules(): List<WorkingHoursRule> {
    val namesByWorkerId = workers.associate { it.workerId to it.displayName }
    return calendars.flatMap { calendar ->
        calendar.workingHours.map { rule ->
            WorkingHoursRule(
                ruleId = rule.ruleId,
                workerId = rule.workerId,
                workerName = namesByWorkerId[rule.workerId] ?: rule.workerId,
                calendarName = calendar.name,
                dayOfWeek = rule.dayOfWeek,
                startsAt = rule.startsAt,
                endsAt = rule.endsAt,
            )
        }
    }
}

/** RFC 7807, read for exactly the one field a refusal needs - the identical, deliberately un-shared
 * copy `KtorBookingsApi`'s own private `ProblemDetailsWireDto` already establishes, restated here
 * rather than imported across adapters for the same reason that copy is not shared with
 * `KtorConversationsApi`'s. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/** See `KtorBookingsApi`'s own `classify` for why this exists instead of a `describe()` copy. */
private fun classify(failure: Exception): BookingsQueueFailure =
    if (failure is IOException) BookingsQueueFailure.Transport else BookingsQueueFailure.Unexpected
