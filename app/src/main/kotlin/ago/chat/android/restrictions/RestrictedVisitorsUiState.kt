package ago.chat.android.restrictions

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.restrictions.VisitorRestriction

/**
 * `26-227`: «Ограниченные посетители» — the tenant-admin oversight screen's own whole state
 * (`docs/design/tenant-modules-restrictions-android.md` §1.3), reached from the Диалоги conversation
 * list's own new `⋮` overflow (that design's own finding #3). The identical three/four-arm
 * Loading/Loaded/Failed vocabulary every other read screen in this app already uses
 * ([ago.chat.android.analytics.PhoneRevealsReportUiState] is the closest sibling shape: a keyset-paged,
 * "load more"-driven admin report with no date-range control of its own).
 *
 * **Rows are held as plain [VisitorRestriction] domain values, not a pre-computed display row.** The
 * design doc's own sketch bakes a `status: RestrictionStatus` onto each row at load time; this app
 * instead computes that status at render time from [ago.chat.android.ui.components.rememberTickingNow]
 * (`ago.chat.android.conversations.ConversationListScreen`'s own established idiom for "what does 'now'
 * mean to this screen" — a Compose-level ticking clock the screen already reads once and passes down,
 * not a `System.now()` read inline, and not a second, domain-layer clock port this codebase has never
 * needed for a purely cosmetic, non-ordering fact). Baking a computed status into this state would mean
 * either re-computing it on every reload (redundant with the render-time read) or a stale label sitting
 * on screen between reloads while the wall clock moves past an `expiresAt` — this state's own [Loaded]
 * arm holds exactly what the server said and nothing this app derived from a clock.
 */
public sealed interface RestrictedVisitorsUiState {
    public data object Loading : RestrictedVisitorsUiState

    /**
     * [rows] — the restrictions returned so far, oldest-appended as [nextBeforeId] pages further back,
     * the identical "a keyset list only ever grows forward" contract
     * [ago.chat.android.analytics.PhoneRevealsReportUiState.Loaded.reveals] already states for its own
     * sibling screen. An empty [rows] with [nextBeforeId] `null` is the genuine "no restrictions on file"
     * state — rendered as a stated empty body, never a spinner.
     *
     * [nextBeforeId] — the keyset cursor for the next page; `null` once the oldest row has been reached,
     * at which point the screen draws no "load more" control at all (hide, not disable — the identical
     * rule [ago.chat.android.analytics.PhoneRevealsReportScreen]'s own doc comment states).
     *
     * [loadingMore] — `true` while one further page is in flight; a failed page leaves [rows] and
     * [nextBeforeId] exactly as they were, so the same "load more" control simply asks again (the
     * identical "a secondary fetch degrades non-destructively" posture
     * [ago.chat.android.thread.contactpanel.ContactPanelViewModel.loadMorePastDialogs]'s own doc comment
     * states).
     *
     * [liftingId] — the *row id* (not the visitor id [ago.chat.android.core.domain.restrictions.VisitorRestrictionApi.lift]
     * is actually addressed by) whose lift is currently in flight, `null` otherwise. A single nullable
     * field rather than a per-row `Set` — deliberately: only one lift is ever in flight across this whole
     * screen at a time (a lift reloads the entire list on success, so a second concurrent lift would
     * either race that reload or act on a row already gone), the identical single-flight-for-the-whole-
     * screen shape [ago.chat.android.analytics.PhoneRevealsReportUiState.Loaded.loadingMore] already
     * takes for its own one paging control.
     *
     * [liftError] — the last lift outcome when it was not a success, shown as a banner and cleared the
     * moment a new lift begins. [rows] is left exactly as it was through either non-success arm — the
     * identical "a refused or failed write never mutates what is already on screen" posture every write
     * in this app already takes.
     */
    public data class Loaded(
        val rows: List<VisitorRestriction>,
        val nextBeforeId: String?,
        val loadingMore: Boolean = false,
        val liftingId: String? = null,
        val liftError: LiftRestrictionError? = null,
    ) : RestrictedVisitorsUiState

    public data class Failed(
        val reason: NetworkFailure,
    ) : RestrictedVisitorsUiState
}

/**
 * `26-227`: why a lift did not take — the two non-success arms of
 * [ago.chat.android.core.domain.restrictions.VisitorRestrictionActionResult], carried into the UI the
 * identical way [ago.chat.android.thread.contactpanel.RestrictionActionError] already carries the
 * contact panel's own lift/block outcome. Held as this sealed type rather than a pre-rendered `String` —
 * localizing a [NetworkFailure] needs `stringResource`, which only a `@Composable` can call, so the
 * wording is chosen in [ago.chat.android.restrictions.RestrictedVisitorsScreen], never here.
 */
public sealed interface LiftRestrictionError {
    /** A genuine server refusal — its RFC 7807 `detail` shown verbatim, the same "show the server's own
     * sentence" posture every other `Refused` arm in this app already establishes. */
    public data class Refused(
        val detail: String,
    ) : LiftRestrictionError

    /** A transport failure — no server sentence to show, so the screen renders one generic,
     * [NetworkFailure]-classified line via [ago.chat.android.ui.components.networkFailureText]. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : LiftRestrictionError
}
