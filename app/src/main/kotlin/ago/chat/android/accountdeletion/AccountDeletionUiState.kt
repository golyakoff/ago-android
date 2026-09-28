package ago.chat.android.accountdeletion

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-252` (`ago-console`'s own `AccountDeletionPage`, ported): Ещё → Администрирование → «Удалить аккаунт» —
 * a warning screen whose single, irreversible action is guarded behind an explicit confirmation dialog, then
 * a terminal "erasing has started" state.
 *
 * The console holds three flags — `confirming`, `submitting`, `erasing` — over one page. This models the same
 * three as two states, because on Android the terminal [Erasing] state replaces the whole screen exactly as
 * the console's `erasing` branch replaces its whole panel (there is nothing left to interact with once the
 * account is being deleted):
 *
 * - [Ready] is the warning panel plus, optionally, the open confirmation dialog ([Ready.confirming]) and a
 *   request in flight ([Ready.submitting]). The erase call is reachable **only** while [Ready.confirming] is
 *   true — see [AccountDeletionViewModel.confirmDeletion]'s own gate.
 * - [Erasing] is the console's own `erasing` panel: the erase has been accepted (`202`), the account is being
 *   deleted, and the only thing left to do is sign out.
 *
 * A refusal or transport failure of the erase call is surfaced inline on the [Ready] state
 * ([Ready.submitError]/[Ready.refusal]) with the dialog dismissed, the identical split
 * [ago.chat.android.siteexport.SiteExportUiState] draws between its page and its inline request error.
 */
public sealed interface AccountDeletionUiState {
    public data class Ready(
        /** The confirmation dialog is open. Set only by [AccountDeletionViewModel.askConfirmation]; the erase
         * call fires only while this is true, which is what makes deletion impossible without a deliberate
         * confirm. */
        val confirming: Boolean = false,
        /** The erase call is in flight — the confirm button shows a spinner and both dialog buttons disable,
         * exactly as the console's own `submitting` flag drives. */
        val submitting: Boolean = false,
        /** A transport failure of the erase call — surfaced inline on the warning panel, the dialog dismissed.
         * `null` unless the last attempt failed at the transport level. */
        val submitError: NetworkFailure? = null,
        /** A genuine server refusal of the erase call (an RFC 7807 `detail`), shown verbatim — distinct from
         * [submitError] the same way the domain's
         * [ago.chat.android.core.domain.accountdeletion.EraseAccountResult.Refused] is distinct from its
         * `Failed`. `null` unless the last attempt was refused. */
        val refusal: String? = null,
    ) : AccountDeletionUiState

    /** The erase was accepted (`202`); the account is being deleted and the operator should sign out — the
     * console's own terminal `erasing` panel. */
    public data object Erasing : AccountDeletionUiState
}
