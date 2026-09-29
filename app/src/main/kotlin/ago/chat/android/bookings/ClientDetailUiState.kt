package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.Contact
import ago.chat.android.core.domain.bookings.PersonBooking
import java.time.OffsetDateTime

/**
 * `26-269`: [ClientDetailViewModel]'s whole state — the identical three/four-arm shape every sibling
 * screen on this port already establishes ([ContactsUiState]/[ConfirmedBookingsUiState]), restated here
 * because [Loaded] carries this hub's own genuinely different shape: a [Contact] the caller already had
 * (Клиенты's own row, never re-fetched — there is no "one contact" read on this port, only the list),
 * plus the two *new* per-client reads this item adds ([upcoming]/[past] bookings, [dialogConversationId]).
 */
internal sealed interface ClientDetailUiState {
    data object Loading : ClientDetailUiState

    /**
     * [contact] is this screen's own copy of the row the operator tapped — reveal/confirm-phone mutate
     * it here, never propagating back to [ContactsViewModel]'s own list, the identical "each screen holds
     * its own copy of a display-merged row" precedent [ConfirmedBookingsUiState.Loaded]'s reveal already
     * establishes for its own, separately-held [ago.chat.android.core.domain.bookings.ConfirmedBooking]
     * rows (a reveal in Утверждены does not update Клиенты either).
     *
     * [upcoming]/[past] are the client-side split of one [PersonBookingsResult.Loaded] answer — see
     * [splitPersonBookings] for the rule. [selectedSegment] is which of the two the body currently shows;
     * held here rather than as a bare `remember` in the composable, the identical "state about *this
     * loaded screen*, not a transient UI toggle" reasoning [ContactsUiState.Loaded.searchQuery]'s own doc
     * comment states for its own field.
     *
     * [dialogConversationId] is `conversations.firstOrNull()?.conversationId` from
     * [ago.chat.android.core.domain.persons.PersonsApi.fetchPersonConversations] — already
     * active-else-most-recent-ordered server-side (that port's own doc comment) — or `null` when the
     * client has no conversation at all (a `26-268` manual client, for one) or when that read failed;
     * either way «Открыть диалог» is hidden, never greyed (`docs/backlog/26-269-*.md` §4's own "hide,
     * don't grey" rule, restated from the confirmed-bookings screen's identical treatment of a null
     * `originConversationId`). This is a deliberate degrade, not folded into [ClientDetailUiState.Failed]:
     * the whole hub must not fail just because the dialog side-read did
     * (`adr/0184` decision 4: "degrades to no dialog to open yet").
     */
    data class Loaded(
        val contact: Contact,
        val upcoming: List<PersonBooking>,
        val past: List<PersonBooking>,
        val dialogConversationId: String?,
        val selectedSegment: ClientDetailSegment = ClientDetailSegment.Upcoming,
        val revealing: Boolean = false,
        val confirmingPhone: Boolean = false,
        val actionError: BookingActionErrorUi? = null,
        /** `26-275`: which upcoming booking's own «Отменить» is on the network right now — a *set*, the
         * identical [ContactsUiState.Loaded.revealingCustomerIds] reasoning restated for a per-row veto
         * write instead of a per-customer reveal: a second row's own cancel must stay tappable while a
         * first is still in flight. Defaulted so every existing call site keeps compiling unchanged. */
        val cancellingBookingIds: Set<String> = emptySet(),
    ) : ClientDetailUiState {
        val hasDialog: Boolean get() = dialogConversationId != null

        val visibleBookings: List<PersonBooking>
            get() = if (selectedSegment == ClientDetailSegment.Upcoming) upcoming else past
    }

    /** The identical "this deployment does not run AGO Calendar at all" fact
     * [ago.chat.android.core.domain.bookings.PendingBookingsResult.NotConfigured]'s own doc comment
     * explains — unreachable in practice (a client-detail hub only ever opens from a Клиенты row, and
     * that segment is itself gated on the calendar being configured), kept for the same "the type must be
     * exhausted honestly" reason [KtorBookingsApi]'s own write methods state for their own unreachable
     * `NotConfigured`-shaped case. */
    data object NotConfigured : ClientDetailUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : ClientDetailUiState
}

/**
 * `26-269`: Предстоящие/Прошедшие — a property of a *booking* (`docs/backlog/26-269-*.md` §3.5/§9: "past/
 * future is a property of a booking, not a client"), so this enum lives beside [ClientDetailUiState]
 * rather than being a client-wide flag. A plain UI-layer enum, the identical "nothing outside this
 * screen's own composables and view-model wiring needs to know these names exist" reasoning
 * [BookingsTab]/[DateStripEdgeLoad] already state for themselves.
 */
internal enum class ClientDetailSegment {
    Upcoming,
    Past,
}

/**
 * `26-269`: the hub's own Предстоящие/Прошедшие split — a booking's [PersonBooking.startsAt] before
 * [now] is Прошедшие, otherwise Предстоящие. [now] is a parameter, not read from inside this function, so
 * a unit test can assert the boundary with a fixed instant rather than depending on the wall clock — the
 * identical "no hidden clock" posture [ago.chat.android.bookings.ConfirmedBookingsViewModel]'s own
 * `anchorDate` field takes for its own date math (an explicit value threaded in and out, never a bare
 * `OffsetDateTime.now()` buried inside the function body).
 *
 * A booking whose [PersonBooking.startsAt] fails to parse sorts into [past] — the same "never let a
 * malformed value crash the split" posture, resolved toward the branch a stale-but-unparseable record is
 * more likely to belong to (a booking made under a scheme this app no longer expects is far more likely
 * old than upcoming).
 *
 * [PersonBooking.status] plays no part in the split itself — a client-side rule and rule alone
 * (`docs/backlog/26-269-*.md` §3.5's own "the split lives where the bookings are"); the design mockup's
 * own examples ([PersonBookingStatus.NoShow] rows only ever appearing under Прошедшие) fall out of the
 * time split alone, since a booking cannot be marked a no-show before its own start time passes.
 *
 * Sort order: [upcoming] ascending by [PersonBooking.startsAt] (the soonest one first — what a phone call
 * about "when am I booked" wants to see); [past] descending (the most recent visit first — a history
 * read top-to-bottom).
 */
internal fun splitPersonBookings(
    bookings: List<PersonBooking>,
    now: OffsetDateTime,
): Pair<List<PersonBooking>, List<PersonBooking>> {
    val (upcoming, past) =
        bookings.partition { booking ->
            val startsAt = runCatching { OffsetDateTime.parse(booking.startsAt) }.getOrNull()
            startsAt != null && startsAt.isAfter(now)
        }
    return upcoming.sortedBy { it.startsAt } to past.sortedByDescending { it.startsAt }
}
