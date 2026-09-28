package ago.chat.android.core.domain.siteexport

import ago.chat.android.core.domain.net.NetworkFailure
import java.time.Instant

/**
 * `26-251` (`ago-console`'s own `SiteExportPage`/`siteExportsApi.ts`): the port behind Администрирование →
 * «Скачать данные» — `GET`/`POST /api/v1/sites/{siteId}/exports`, both gated server-side on
 * `Ago.Chat.Domain.Permission.SiteExport` (the same `site:export` the row itself is hidden without). The
 * identical "declared here, implemented in `:core:network`" split
 * [ago.chat.android.core.domain.consent.SiteConsentDocumentsApi] establishes — the dependency rule is
 * what puts it here rather than beside the Ktor client: a view model holding an `HttpClient` directly
 * could not be tested without one, and every HTTP-shaped decision (which status means what, which base
 * URL, which `{siteId}`) belongs on the far side of this interface, in the adapter.
 *
 * **No poll.** Console `SiteExportPage`'s own doc comment states the design plainly: requesting an export
 * reloads the history list once (showing the new `Pending` row), and nothing polls that row forward to
 * `Ready` on its own — an operator who wants to see the outcome re-reads the list. This port mirrors that:
 * two methods, no status-poll endpoint of its own.
 */
public interface SiteExportApi {
    /**
     * `GET /api/v1/sites/{siteId}/exports` — every export request this site has ever made, newest first.
     * A bare array (`GetSiteExportHistoryHandler`'s own "small and bounded, no pagination" reasoning),
     * the same shape the console reads without a cursor.
     */
    public suspend fun fetchHistory(): SiteExportHistoryResult

    /**
     * `POST /api/v1/sites/{siteId}/exports` — `202 Accepted`, the same "accepted, not yet done" shape the
     * console's own `requestSiteExport` states: nothing is ready when this resolves, only queued
     * (`Ago.Chat.Worker`'s `SiteExportJob` builds the archive off its own timer). The caller reloads the
     * history after a [RequestSiteExportResult.Requested], which is what shows the new `Pending` row.
     */
    public suspend fun requestExport(): RequestSiteExportResult
}

/**
 * `Ago.Chat.Domain.ExportStatus`'s member names, mirrored verbatim — the same "server names the state,
 * client spells it the same way" contract the console's own `SiteExportStatus` union keeps. [Processing]
 * is a request a `Worker` replica has atomically claimed and is currently building/uploading, between
 * [Pending] (not yet claimed) and [Ready]/[Failed] (done); [Expired] is a once-`Ready` archive the prune
 * job has since removed.
 */
public enum class SiteExportStatus {
    Pending,
    Processing,
    Ready,
    Failed,
    Expired,
}

/**
 * One past export request — the app-side shape of `SiteExportHistoryItemResponse`, field for field.
 * [downloadUrl] and [expiresAt] are populated only for a [SiteExportStatus.Ready] row
 * (`GetSiteExportHistoryHandler`'s own remarks on why); [failureReason] only for a [SiteExportStatus.Failed]
 * one. [completedAt] is `null` while the export is still [SiteExportStatus.Pending]/[SiteExportStatus.Processing].
 */
public data class SiteExportHistoryItem(
    val exportId: String,
    val status: SiteExportStatus,
    val requestedAt: Instant,
    val completedAt: Instant?,
    val downloadUrl: String?,
    val expiresAt: Instant?,
    val failureReason: String?,
)

/** What reading the history came back with — the identical two-arm shape
 * [ago.chat.android.core.domain.consent.ConsentAcceptancesResult] already establishes. A loaded-but-empty
 * list is a real, expected state (nothing exported yet), never a failure. */
public sealed interface SiteExportHistoryResult {
    public data class Loaded(
        val items: List<SiteExportHistoryItem>,
    ) : SiteExportHistoryResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : SiteExportHistoryResult
}

/** What requesting a new export came back with — three arms, the identical shape
 * [ago.chat.android.core.domain.consent.ConsentPublishResult] establishes minus its `409`-specific
 * `Conflict` (a repeated export request is never a conflict, only another queued row). */
public sealed interface RequestSiteExportResult {
    /** `202 Accepted`, carrying the server's own new export id — reduced to what the history reload will
     * show again anyway; the view model never trusts this over a fresh [SiteExportApi.fetchHistory]. */
    public data class Requested(
        val exportId: String,
    ) : RequestSiteExportResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail` (`Site.NotFound`, a permission refusal,
     * …), shown to the operator verbatim. */
    public data class Refused(
        val detail: String,
    ) : RequestSiteExportResult

    /** Everything that is not a genuine server refusal — a dropped connection, or a non-2xx whose body
     * carried no `detail` to show. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : RequestSiteExportResult
}
