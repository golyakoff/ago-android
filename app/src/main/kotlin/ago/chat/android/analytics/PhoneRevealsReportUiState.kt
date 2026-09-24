package ago.chat.android.analytics

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.PhoneReveal

/**
 * `26-74`: [PhoneRevealsReportViewModel]'s whole state — the fifth and last of the five administrator
 * reports `26-58` decided to port, and the only one built on
 * [ago.chat.android.core.domain.bookings.BookingsApi] rather than a bespoke `*ReportApi` port: this
 * report reads `Ago.Calendar.Api`'s own audit trail, not `Ago.Chat.Api`'s conversation analytics, so it
 * can be genuinely [NotConfigured] the way none of [ConversionReportUiState]/[TagBreakdownReportUiState]/
 * [BookingFunnelReportUiState] ever are (`docs/backlog/26-74-*.md`'s own Scope item 1).
 *
 * A four-arm shape of its own rather than a reuse of [ago.chat.android.bookings.ContactsUiState]
 * verbatim — the two share [NotConfigured]/[Failed] in spirit, but [Loaded] here also carries this
 * screen's own paging state ([Loaded.nextBefore]/[Loaded.loadingMore]/[Loaded.loadMoreFailed]), which
 * that read has no equivalent of (`ContactsResult`/`ConfirmedBookingsResult` are not paged at all).
 */
public sealed interface PhoneRevealsReportUiState {
    public data object Loading : PhoneRevealsReportUiState

    /**
     * `docs/backlog/26-74-*.md`'s own Scope item 5: keyset paging, `nextBefore` driving a "load more"
     * control — `null` once the oldest row has been reached, at which point [ago.chat.android.analytics.PhoneRevealsReportScreen]
     * simply draws no such control at all, the same "the control disappears, it does not disable" shape
     * every hide-rather-than-grey control in this app already takes.
     *
     * [loadingMore]/[loadMoreFailed] are this screen's own in-flight/failure state for that one control —
     * a failed "load more" leaves [reveals] exactly as they were and is shown beside the control rather
     * than replacing the list, the identical "a refusal never hides the rows the operator was just
     * looking at" rule [ago.chat.android.bookings.BookingsUiState.Loaded]'s own `actionError` states for
     * a different screen's own in-place failure.
     */
    public data class Loaded(
        val reveals: List<PhoneReveal>,
        val nextBefore: String?,
        val loadingMore: Boolean = false,
        val loadMoreFailed: BookingsQueueFailure? = null,
    ) : PhoneRevealsReportUiState

    /** `docs/backlog/26-74-*.md`'s own Scope item 6, one of three states that must all be reachable and
     * visibly distinct from one another: this deployment has no AGO Calendar backend configured at all —
     * the identical fact [ago.chat.android.core.domain.bookings.PendingBookingsResult.NotConfigured]'s
     * own doc comment explains, and the reason this report can reach a state none of its four siblings
     * can. */
    public data object NotConfigured : PhoneRevealsReportUiState

    public data class Failed(
        val reason: BookingsQueueFailure,
    ) : PhoneRevealsReportUiState
}
