package ago.chat.android.thread.contactpanel

import ago.chat.android.core.domain.contactdetails.ContactDetail
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.tags.ConversationTag
import ago.chat.android.core.domain.tags.Tag
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
    val tags: TagsSectionState = TagsSectionState.Loading,
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

/**
 * `26-149`: the tags section's async state — the second section slice to grow [ContactPanelUiState] the
 * additive way its own doc comment prescribes, reading and writing through
 * [ago.chat.android.core.domain.tags.ConversationTagsApi] (`26-115`). Three arms, the same
 * Loading/Loaded/Failed vocabulary [HeaderSummaryState] and [ContactDetailsSectionState] establish, so the
 * section renders a skeleton while it loads, the chips + «+ метка» control once they land, and its own
 * inline retry (never a whole-sheet failure — `docs/design/26-111-thread-contact-detail-panel.md` §4) if
 * it does not.
 *
 * **Why one [Loaded] carries both lists.** The section needs two reads — the conversation's own applied
 * tags (`fetchConversationTags`, the chips) and the site's whole tag vocabulary (`fetchSiteTags`, what the
 * «+ метка» picker offers minus the applied ones). Rather than two arms that can each be in a different
 * Loading/Failed state (doubling this type for a picker that a single retry fixes anyway), the arm lands
 * [Loaded] only when the **applied-tags** read — the primary content — succeeds, and folds whatever the
 * vocabulary read returned into [vocabulary]; a vocabulary read that itself failed simply yields an empty
 * [vocabulary] (the picker then offers nothing, non-destructively), the same "a secondary read degrades to
 * empty rather than failing the section it decorates" posture the chips-first shape here takes. The
 * alternative — failing the whole section when only the vocabulary read failed — would hide the readable
 * chips behind a retry the operator did not need.
 *
 * The two write-transient fields live on [Loaded] rather than on [ContactPanelUiState] itself, the
 * identical "the in-flight set travels with the loaded list it acts on" shape
 * [ContactDetailsSectionState.Loaded] already establishes for the reveal.
 */
public sealed interface TagsSectionState {
    public data object Loading : TagsSectionState

    /**
     * The conversation's applied tags and the site's tag vocabulary, plus the per-write transient state.
     *
     * [applied] — the tags currently on this conversation, drawn as chips (each removable when the
     * operator holds `conversation:tag`).
     *
     * [vocabulary] — the site's whole tag vocabulary; the «+ метка» picker offers this list **minus**
     * [applied] (that subtraction is a UI concern, [ConversationTagsApi][ago.chat.android.core.domain.tags.ConversationTagsApi.fetchSiteTags]'s
     * own doc comment). Empty when the vocabulary read itself failed — the picker then offers nothing.
     *
     * [pendingTagIds] — the tag ids with an apply or a remove in flight right now; the section disables
     * that chip's remove and that picker entry while its id is in this set (keyed by tag id, since both
     * writes act on a tag id).
     *
     * [actionError] — the last write outcome when it was not a success, shown non-destructively as one
     * line beneath the chips and cleared the moment a new write begins. The applied set is left exactly as
     * it was through either arm — a refused or failed write never mutates the on-screen tags.
     */
    public data class Loaded(
        val applied: List<ConversationTag>,
        val vocabulary: List<Tag>,
        val pendingTagIds: Set<String> = emptySet(),
        val actionError: TagActionError? = null,
    ) : TagsSectionState

    public data class Failed(
        val reason: NetworkFailure,
    ) : TagsSectionState
}

/**
 * `26-149`: why an apply or remove did not take — the two non-success arms of
 * [ago.chat.android.core.domain.tags.TagActionResult], carried into the UI so the section can show a
 * genuine server refusal verbatim but a transport failure as its own generic, localized line — the
 * identical split [RowRevealError] draws for the reveal. The successful arm needs no representation: it
 * updates the applied list in place.
 */
public sealed interface TagActionError {
    /** A genuine server refusal — its RFC 7807 `detail` shown verbatim, the same "show the server's own
     * sentence" posture [RowRevealError.Refused] establishes. */
    public data class Refused(
        val detail: String,
    ) : TagActionError

    /** A transport failure — no server sentence to show, so the section renders one generic
     * «Не удалось изменить метки» line, classified by [reason] only if a caller ever needs to. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : TagActionError
}
