package ago.chat.android.core.network.siteexport

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.siteexport.RequestSiteExportResult
import ago.chat.android.core.domain.siteexport.SiteExportApi
import ago.chat.android.core.domain.siteexport.SiteExportHistoryItem
import ago.chat.android.core.domain.siteexport.SiteExportHistoryResult
import ago.chat.android.core.domain.siteexport.SiteExportStatus
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

/**
 * `26-251` (`ago-console`'s own `siteExportsApi.ts`): the adapter behind [SiteExportApi] — the same
 * "the whole status-code-to-meaning mapping lives here, and only here" shape
 * [ago.chat.android.core.network.consent.KtorSiteConsentDocumentsApi] already establishes.
 *
 * [ActiveSiteSelection.currentSiteId] is read directly here, for both methods — the identical reason
 * that adapter's own doc comment gives: `{siteId}` is in the URL itself
 * (`SitesEndpoints`'s own `{siteId:guid}` route, "siteId from the route, not `user.GetSiteId()`"), not
 * only in the `X-Ago-Active-Site` header every request already gets from `installAgoRestDefaults`. The
 * bearer token is attached by that same client plugin, never threaded through any method here.
 *
 * **A parse failure on an otherwise-2xx body is [NetworkFailure.Unexpected], never an empty read** — the
 * identical discipline every adapter in this app follows. Timestamps are parsed with
 * [OffsetDateTime.parse] and the status with [SiteExportStatus.valueOf]; a malformed timestamp or an
 * unknown status name throws, and the surrounding `try`/`catch` around the whole body-to-domain mapping
 * turns that into [NetworkFailure.Unexpected] rather than a fabricated value.
 */
public class KtorSiteExportApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : SiteExportApi {
    /** `GET /api/v1/sites/{siteId}/exports` — a raw JSON array, no wrapper object. */
    override suspend fun fetchHistory(): SiteExportHistoryResult {
        val siteId = activeSite.currentSiteId() ?: return SiteExportHistoryResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get(exportsUrl(siteId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return SiteExportHistoryResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            // Reachable only for an operator this app already believes holds `site:export` (`MoreScreen`'s
            // own gate), so "could not load, try again" is the honest thing to say — the identical
            // reasoning `KtorSiteConsentDocumentsApi`'s own doc comment records for its own sibling read.
            return SiteExportHistoryResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            SiteExportHistoryResult.Loaded(response.body<List<HistoryItemWireDto>>().map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            SiteExportHistoryResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `POST /api/v1/sites/{siteId}/exports` — `202 Accepted` carrying `{exportId}`; any other non-2xx is
     * read for an RFC 7807 `detail` the identical way
     * [ago.chat.android.core.network.consent.KtorSiteConsentDocumentsApi.publish] describes. */
    override suspend fun requestExport(): RequestSiteExportResult {
        val siteId = activeSite.currentSiteId() ?: return RequestSiteExportResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post(exportsUrl(siteId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return RequestSiteExportResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                RequestSiteExportResult.Requested(response.body<RequestResponseWireDto>().exportId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                RequestSiteExportResult.Failed(NetworkFailure.from(failure))
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

        return detail?.let { RequestSiteExportResult.Refused(it) }
            ?: RequestSiteExportResult.Failed(NetworkFailure.ServerError(response.status.value))
    }

    private fun exportsUrl(siteId: String): String = "$apiBaseUrl/api/v1/sites/$siteId/exports"
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared copy
 * every adapter in this codebase keeps for itself. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/** `SitesEndpoints.SiteExportHistoryItemResponse`, field for field. Nullable fields are absent for a row
 * that has not reached the state that populates them (`downloadUrl`/`expiresAt` only when `Ready`,
 * `failureReason` only when `Failed`, `completedAt` not while still queued). */
@Serializable
private data class HistoryItemWireDto(
    val exportId: String,
    val status: String,
    val requestedAt: String,
    val completedAt: String? = null,
    val downloadUrl: String? = null,
    val expiresAt: String? = null,
    val failureReason: String? = null,
)

/** `SitesEndpoints.RequestSiteExportResponse` — the `202` body, its one field the new export's id. */
@Serializable
private data class RequestResponseWireDto(
    val exportId: String,
)

private fun String.toInstant(): Instant = OffsetDateTime.parse(this).toInstant()

private fun HistoryItemWireDto.toDomain() =
    SiteExportHistoryItem(
        exportId = exportId,
        status = SiteExportStatus.valueOf(status),
        requestedAt = requestedAt.toInstant(),
        completedAt = completedAt?.toInstant(),
        downloadUrl = downloadUrl,
        expiresAt = expiresAt?.toInstant(),
        failureReason = failureReason,
    )
