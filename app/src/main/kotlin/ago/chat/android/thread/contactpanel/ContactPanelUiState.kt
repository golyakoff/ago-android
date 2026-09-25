package ago.chat.android.thread.contactpanel

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.visitorsummary.VisitorSummary

/**
 * `26-147`: the contact-detail panel's own state — the join point every later section
 * (`26-148`…`26-153`) grows rather than replaces.
 *
 * **Why the header's in-hand facts (H1–H3) are not held here.** The avatar, the display name and the
 * state chip come straight from the [ago.chat.android.core.domain.conversations.ConversationSummary] the
 * thread already holds — `docs/design/26-111-thread-contact-detail-panel.md` §4 ("handed the
 * `ConversationSummary` the thread already holds … no extra round trip for those"). They flow into
 * [ContactDetailPanel] as plain parameters, exactly as they already flow into
 * [ago.chat.android.thread.ThreadScreen], so this state carries only the *async* part of the header —
 * H4/H5, read through [ago.chat.android.core.domain.visitorsummary.VisitorSummaryApi] — and, later, each
 * section's own async state.
 *
 * **Growth convention for `26-148`…`26-153`.** A section that needs its own async read adds one more
 * field here (its own `sealed interface` of Loading/Loaded/Failed, the shape [summary] already
 * establishes), defaulted so this data class's existing construction sites compile unchanged — the same
 * additive discipline [ago.chat.android.core.domain.conversations.ConversationSummary]'s own doc comment
 * states for the wire DTO it mirrors.
 */
public data class ContactPanelUiState(
    val summary: HeaderSummaryState = HeaderSummaryState.Loading,
)

/**
 * The header's async half — H4 «Первый визит {date}» and H5 «N диалог(ов)», both from one
 * `visitor-summary` read (`26-143`). Three arms, the identical Loading/Loaded/Failed vocabulary the
 * sibling clients use, so the panel renders a skeleton while it loads, the two facts once it lands, and
 * an inline retry (never a whole-sheet failure — §4) if it does not: the in-hand H1–H3 stay on screen
 * through every arm.
 */
public sealed interface HeaderSummaryState {
    public data object Loading : HeaderSummaryState

    public data class Loaded(
        val summary: VisitorSummary,
    ) : HeaderSummaryState

    public data class Failed(
        val reason: NetworkFailure,
    ) : HeaderSummaryState
}
