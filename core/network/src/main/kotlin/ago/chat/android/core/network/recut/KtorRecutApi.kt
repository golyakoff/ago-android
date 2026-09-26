package ago.chat.android.core.network.recut

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.recut.RecutApi
import ago.chat.android.core.domain.recut.RecutBooking
import ago.chat.android.core.domain.recut.RecutBookingDecision
import ago.chat.android.core.domain.recut.RecutBookingStatus
import ago.chat.android.core.domain.recut.RecutConfirmResult
import ago.chat.android.core.domain.recut.RecutConfirmation
import ago.chat.android.core.domain.recut.RecutDay
import ago.chat.android.core.domain.recut.RecutDecision
import ago.chat.android.core.domain.recut.RecutPreview
import ago.chat.android.core.domain.recut.RecutPreviewResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `26-168`: the adapter behind [RecutApi] — the same "the whole status-code-to-meaning mapping lives
 * here, and only here" shape [ago.chat.android.core.network.workers.KtorWorkersApi] already establishes,
 * and the same check-[calendarApiBaseUrl]-first, classify-never-invent discipline.
 *
 * **`type`, not just `detail`, on both writes.** [RecutApi]'s own class doc comment explains why
 * `recut.stale` must be told apart from every other refusal — [refusal] reads the server's own RFC 7807
 * `type` into every [RecutPreviewResult.Refused]/[RecutConfirmResult.Refused] it builds, the identical
 * shape [ago.chat.android.core.network.analytics.KtorSiteAnalyticsApi] already reads `Analytics.InvalidRange`
 * with, restated here because both this port's writes need it, not just one read.
 *
 * **Never a raw exception class name or a hostname on screen** — [classify] answers only "no network" or
 * "something else", by exception type, never by printing `this::class.simpleName` or the exception's own
 * `message` (`UnknownHostException`'s message quotes the host it failed to resolve).
 */
public class KtorRecutApi(
    private val client: HttpClient,
    private val calendarApiBaseUrl: String?,
) : RecutApi {
    override suspend fun preview(
        workerId: String,
        from: String,
    ): RecutPreviewResult {
        require(workerId.isNotBlank()) { "A worker id is never blank." }
        val baseUrl = calendarApiBaseUrl ?: return RecutPreviewResult.NotConfigured

        val response =
            try {
                client.post("$baseUrl/api/v1/console/workers/$workerId/schedule/recut/preview") {
                    contentType(ContentType.Application.Json)
                    setBody(RecutPreviewRequestWireDto(from))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return RecutPreviewResult.Failed(classify(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                RecutPreviewResult.Loaded(response.body<RecutPreviewResponseWireDto>().toDomain())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                RecutPreviewResult.Failed(classify(failure))
            }
        }

        return refusal(response, RecutPreviewResult::Refused) ?: RecutPreviewResult.Failed(BookingsQueueFailure.Unexpected)
    }

    /**
     * `POST /api/v1/console/workers/{workerId}/schedule/recut` — [decisions] travel as the server's own
     * `"Cancel"`/`"Keep"` spellings, translated here rather than left to the caller so no caller of this
     * port can typo a decision string the server would refuse as `recut.invalid`.
     */
    override suspend fun confirm(
        workerId: String,
        from: String,
        fingerprint: String,
        decisions: List<RecutBookingDecision>,
    ): RecutConfirmResult {
        require(workerId.isNotBlank()) { "A worker id is never blank." }
        val baseUrl = calendarApiBaseUrl ?: return RecutConfirmResult.NotConfigured

        val response =
            try {
                client.post("$baseUrl/api/v1/console/workers/$workerId/schedule/recut") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        RecutConfirmRequestWireDto(
                            from = from,
                            fingerprint = fingerprint,
                            decisions = decisions.map { it.toWireDto() },
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return RecutConfirmResult.Failed(classify(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                RecutConfirmResult.Confirmed(response.body<RecutConfirmResponseWireDto>().toDomain())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                RecutConfirmResult.Failed(classify(failure))
            }
        }

        return refusal(response, RecutConfirmResult::Refused) ?: RecutConfirmResult.Failed(BookingsQueueFailure.Unexpected)
    }

    /**
     * The one place both writes' non-2xx handling lives — they differ only in the type they build a
     * refusal into. `null` only when the body carried no genuine `detail` at all, in which case the
     * caller falls back to [BookingsQueueFailure.Unexpected] — the identical "never a fabricated
     * sentence" rule every write in this app already follows. A missing `type` on a body that did carry
     * a `detail` becomes an empty [String] rather than `null` — [RecutPreviewResult.Refused.code]'s own
     * doc comment states why that case is not a parse failure.
     */
    private suspend fun <T> refusal(
        response: HttpResponse,
        build: (detail: String, code: String) -> T,
    ): T? {
        val problem =
            try {
                response.body<ProblemDetailsWireDto>()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }
        val detail = problem?.detail ?: return null
        return build(detail, problem.type ?: "")
    }
}

@Serializable
private data class RecutPreviewRequestWireDto(
    val from: String,
)

/** `Ago.Calendar.Contracts.RecutPreviewResponse`, field for field. */
@Serializable
private data class RecutPreviewResponseWireDto(
    val days: List<RecutDayWireDto> = emptyList(),
    val fingerprint: String,
)

/** `Ago.Calendar.Contracts.RecutDayPreviewResponse`, field for field. */
@Serializable
private data class RecutDayWireDto(
    val localDate: String,
    val availableSlotsToDelete: Int,
    val bookings: List<RecutBookingWireDto> = emptyList(),
)

/** `Ago.Calendar.Contracts.RecutBookingPreviewResponse`, field for field. */
@Serializable
private data class RecutBookingWireDto(
    val bookingId: String,
    val startsAt: String,
    val endsAt: String,
    val status: String,
    val serviceId: String? = null,
    val serviceName: String? = null,
    val personId: String? = null,
    val phone: String? = null,
    val masked: Boolean = false,
    val canDecide: Boolean,
)

private fun RecutPreviewResponseWireDto.toDomain() =
    RecutPreview(
        days = days.map { it.toDomain() },
        fingerprint = fingerprint,
    )

private fun RecutDayWireDto.toDomain() =
    RecutDay(
        localDate = localDate,
        availableSlotsToDelete = availableSlotsToDelete,
        bookings = bookings.map { it.toDomain() },
    )

private fun RecutBookingWireDto.toDomain() =
    RecutBooking(
        bookingId = bookingId,
        startsAt = startsAt,
        endsAt = endsAt,
        status = status.toRecutBookingStatus(),
        rawStatus = status,
        serviceId = serviceId,
        serviceName = serviceName,
        personId = personId,
        phone = phone,
        masked = masked,
        canDecide = canDecide,
    )

/** [RecutBookingStatus]'s own doc comment states why an unrecognised spelling becomes
 * [RecutBookingStatus.Unknown] rather than a parse failure. */
private fun String.toRecutBookingStatus(): RecutBookingStatus =
    when (this) {
        "PendingConfirmation" -> RecutBookingStatus.PendingConfirmation
        "Booked" -> RecutBookingStatus.Booked
        "NoShow" -> RecutBookingStatus.NoShow
        else -> RecutBookingStatus.Unknown
    }

/** `Ago.Calendar.Contracts.RecutConfirmRequest`, field for field. */
@Serializable
private data class RecutConfirmRequestWireDto(
    val from: String,
    val fingerprint: String,
    val decisions: List<RecutDecisionRequestWireDto>,
)

/** `Ago.Calendar.Contracts.RecutDecisionRequest`, field for field. */
@Serializable
private data class RecutDecisionRequestWireDto(
    val bookingId: String,
    val decision: String,
)

private fun RecutBookingDecision.toWireDto() =
    RecutDecisionRequestWireDto(
        bookingId = bookingId,
        decision =
            when (decision) {
                RecutDecision.Cancel -> "Cancel"
                RecutDecision.Keep -> "Keep"
            },
    )

/** `Ago.Calendar.Contracts.RecutConfirmResponse`, field for field. */
@Serializable
private data class RecutConfirmResponseWireDto(
    val recutDays: List<String> = emptyList(),
    val skippedDays: List<String> = emptyList(),
    val slotsDeleted: Int,
    val slotsInserted: Int,
    val bookingsCancelled: Int,
)

private fun RecutConfirmResponseWireDto.toDomain() =
    RecutConfirmation(
        recutDays = recutDays,
        skippedDays = skippedDays,
        slotsDeleted = slotsDeleted,
        slotsInserted = slotsInserted,
        bookingsCancelled = bookingsCancelled,
    )

/** RFC 7807, read for the two fields this adapter needs — `detail` for every refusal, `type` for the
 * `code` [RecutApi]'s own class doc comment explains both writes need. A second, deliberately un-shared
 * declaration — [ago.chat.android.core.network.workers.KtorWorkersApi]'s own private `ProblemDetailsWireDto`
 * states why. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
    val type: String? = null,
)

/** See `KtorWorkersApi`'s own `classify` for why this exists instead of a `describe()` copy. */
private fun classify(failure: Exception): BookingsQueueFailure =
    if (failure is IOException) BookingsQueueFailure.Transport else BookingsQueueFailure.Unexpected
