package ago.chat.android.thread.contactpanel

import ago.chat.android.core.domain.contactdetails.ContactDetail
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
    val contactDetails: ContactDetailsSectionState = ContactDetailsSectionState.Loading,
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

/**
 * `26-148`: the КОНТАКТНЫЕ ДАННЫЕ section's async state — the first section slice to grow
 * [ContactPanelUiState] the additive way its own doc comment describes, reading the visitor's contact
 * rows through [ago.chat.android.core.domain.contactdetails.ContactDetailsApi] (`26-115`). Three arms, the
 * same Loading/Loaded/Failed vocabulary [HeaderSummaryState] establishes, so the section renders a
 * skeleton while it loads, the rows once they land, and its own inline retry (never a whole-sheet
 * failure — `docs/design/26-111-thread-contact-detail-panel.md` §4) if it does not.
 *
 * The two reveal-transient fields live on the [Loaded] arm rather than on [ContactPanelUiState] itself,
 * the identical "the in-flight set travels with the loaded list it acts on" shape
 * [ago.chat.android.bookings.ContactsUiState.Loaded] already establishes for the calendar reveal.
 */
public sealed interface ContactDetailsSectionState {
    public data object Loading : ContactDetailsSectionState

    /**
     * The rows the server returned, plus the per-row reveal transient state.
     *
     * [revealingIds] — the contact-detail ids with a reveal in flight right now; the section disables that
     * row's «Показать» and swaps its label to «Показ…» while its id is in this set (keyed by row id, since
     * a reveal is a single-row action, unlike the calendar's per-customer key).
     *
     * [revealErrors] — the last reveal outcome per row when it was not a success: a [RowRevealError.Refused]
     * carries the server's own RFC 7807 sentence to show verbatim, a [RowRevealError.Failed] a transport
     * cause the section renders as one generic line. The masked value stays on screen through either — this
     * app never unmasks a value from a failure (`ContactDetailsApi.revealContactDetail`'s own contract).
     */
    public data class Loaded(
        val details: List<ContactDetail>,
        val revealingIds: Set<String> = emptySet(),
        val revealErrors: Map<String, RowRevealError> = emptyMap(),
    ) : ContactDetailsSectionState

    public data class Failed(
        val reason: NetworkFailure,
    ) : ContactDetailsSectionState
}

/**
 * `26-148`: why one row's reveal did not replace its masked value — the two non-success arms of
 * [ago.chat.android.core.domain.contactdetails.RevealContactDetailResult], carried into the UI so the
 * section can show a genuine server refusal verbatim but a transport failure as its own generic,
 * localized line. The successful arm needs no representation here: it replaces the row in place.
 */
public sealed interface RowRevealError {
    /** A genuine server refusal — its RFC 7807 `detail` shown verbatim, the same "show the server's own
     * sentence" posture the reveal-refusal arm establishes. */
    public data class Refused(
        val detail: String,
    ) : RowRevealError

    /** A transport failure — no server sentence to show, so the section renders one generic
     * «Не удалось показать» line, classified by [reason] only if a caller ever needs to. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : RowRevealError
}
