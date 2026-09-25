package ago.chat.android.channels

import ago.chat.android.core.domain.bookings.BookingsQueueFailure

/**
 * `26-159`: [InstallWidgetViewModel]'s whole state — the identical loaded/failed shape the app's read
 * screens already establish (`ConversionReportUiState`, `WorkingHoursUiState`), with no `NotConfigured`
 * arm: the chat installation always exists in a chat deployment
 * ([ago.chat.android.core.domain.installation.SiteInstallationResult]'s own doc comment).
 */
internal sealed interface InstallWidgetUiState {
    data object Loading : InstallWidgetUiState

    /**
     * @param embedSnippet the ready-to-paste `<script>` tag, composed once in the view model from the
     *   site's public key and the widget host — shown read-only, the identical "the console is the only
     *   place the key appears" posture `ago-console`'s own `InstallSnippetPage` records.
     * @param allowedOrigins the origins the widget's browser-side check compares against, in the server's
     *   own order. Read-only — `Ago.Chat.Api` exposes no `site:configure`-gated write for them.
     */
    data class Loaded(
        val embedSnippet: String,
        val allowedOrigins: List<String>,
    ) : InstallWidgetUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : InstallWidgetUiState
}
