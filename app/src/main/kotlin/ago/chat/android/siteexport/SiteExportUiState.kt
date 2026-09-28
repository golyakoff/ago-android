package ago.chat.android.siteexport

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.siteexport.SiteExportHistoryItem

/**
 * `26-251` (`ago-console`'s own `SiteExportPage`, ported): Ещё → Администрирование → «Скачать данные» —
 * the export-history list plus one "request export" action. The identical three-arm Loading/Loaded/Failed
 * vocabulary every other site-scoped screen in this app uses ([ago.chat.android.products.ProductsUiState]
 * is the closest read-only sibling; the write half's in-`Loaded` `requesting`/error flags mirror
 * [ago.chat.android.automation.TagsUiState]'s own).
 *
 * **The history read is the screen; the request is a secondary action over it.** A failed *history* read
 * is a whole-screen [Failed]; a failed *request* leaves the list on screen and surfaces the failure inline
 * ([Loaded.requestError]/[Loaded.requestRefusal]), the identical split the console draws between its
 * page-level `state` and its inline `requestError`.
 */
public sealed interface SiteExportUiState {
    public data object Loading : SiteExportUiState

    public data class Loaded(
        val items: List<SiteExportHistoryItem>,
        /** A request-export call is in flight — the button shows a spinner and disables, exactly as the
         * console's own `requesting` flag drives. */
        val requesting: Boolean = false,
        /** A transport failure from the request-export action — surfaced inline without discarding the
         * list already on screen, unlike the whole-screen [Failed]. `null` when the last request
         * succeeded or none has run. */
        val requestError: NetworkFailure? = null,
        /** A genuine server refusal of the request-export action (an RFC 7807 `detail`), shown verbatim —
         * distinct from [requestError] the same way the domain's
         * [ago.chat.android.core.domain.siteexport.RequestSiteExportResult.Refused] is distinct from its
         * `Failed`. `null` unless the last request was refused. */
        val requestRefusal: String? = null,
    ) : SiteExportUiState

    public data class Failed(
        val reason: NetworkFailure,
    ) : SiteExportUiState
}
