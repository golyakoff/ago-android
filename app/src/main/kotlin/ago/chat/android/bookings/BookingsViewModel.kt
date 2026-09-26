package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingActionResult
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.PendingBooking
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import ago.chat.android.core.domain.bookings.oldestDeadlineFirst
import ago.chat.android.core.domain.persons.PersonsApi
import ago.chat.android.core.domain.persons.PersonsResult
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-48`: Записи's own «Ожидают» state — one real network call, no cache, no polling and no hub
 * overlay, unlike [ago.chat.android.conversations.ConversationListViewModel]. This screen's one
 * promise is a read-only queue (`docs/backlog/26-48-*.md`'s own Scope); a live-update channel and a
 * client-side cache are both real engineering, and neither is this item's to add on spec alone
 * (`26-51`/a later item is where "keep this screen live" would actually get proven against a Done-when
 * of its own).
 *
 * `26-49`: no longer read-only — [reject]/[cancel] are the queue's veto verbs. [busyBookingIds] is plain
 * instance state, not part of [BookingsUiState] itself until [applyPendingResult] folds it in — the
 * identical "no lock needed" reasoning [ConversationListViewModel]'s own class doc comment gives: every
 * mutation here runs on `viewModelScope`'s own dispatcher, and the `withContext(ioDispatcher)` blocks only
 * ever wrap the suspending network call, never a read or write of this field.
 *
 * `26-163`: **`markNoShow` is gone from this class, on purpose.** `Event.MarkNoShow` (`Ago.Calendar.Domain`)
 * accepts only a `Booked` row - a no-show is a statement about a *confirmed* visit that did not happen, and
 * every row on this screen is still `PendingConfirmation`, so the server refused the call on every row it
 * could ever have been aimed at. The port method ([BookingsApi.markNoShow]) stays: it is the backend's real
 * surface, and Утверждены's own detail sheet is where a caller for it belongs. What *is* valid for a pending
 * row is exactly [reject] (`Event.Reject`: `PendingConfirmation -> Cancelled`) and [cancel] (`Event.Cancel`:
 * `PendingConfirmation | Booked -> Cancelled`, a second permission for the same transition - see
 * `CancelBookingHandler`'s own remarks on why the queue offers both). There is no operator *confirm*: the
 * calendar exposes no such endpoint (`ConsoleEndpoints.cs` maps reject/cancel/no-show and nothing else;
 * `Event.Confirm` is the sweep's alone), so the screen's own caption - everything confirms automatically -
 * is the whole accept path, stated rather than drawn as a button the server would not answer.
 *
 * `26-163`/`adr/0184`: [personsApi] is the identical display-merge
 * [ago.chat.android.bookings.ConfirmedBookingsViewModel]'s own doc comment describes for Утверждены,
 * restated here because `PendingBookingResponse` carries the identical bare `personId`-no-name shape.
 * [mergeDisplayNames] is the one place this class closes that gap.
 */
@HiltViewModel
public class BookingsViewModel
    @Inject
    constructor(
        private val api: BookingsApi,
        private val personsApi: PersonsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<BookingsUiState>(BookingsUiState.Loading)
        public val state: StateFlow<BookingsUiState> = mutableState.asStateFlow()

        /** `26-49`: which rows have a write in flight right now — a *set*, since a second row's own
         * action must stay tappable while a first is still out on the network
         * ([BookingsUiState.Loaded]'s own doc comment on [BookingsUiState.Loaded.busyBookingIds]). */
        private var busyBookingIds: Set<String> = emptySet()

        init {
            refresh()
        }

        /** The initial load, and the retry action a [BookingsUiState.Failed] screen offers — the same
         * function for both, since a retry is simply "ask again", never a second code path
         * (`ClaimResult.Refused`'s own doc comment states the identical "never a silent retry" rule for
         * the opposite case; here the *operator* decides to retry, explicitly, every time). Clears
         * [busyBookingIds]: a fresh, explicit reload is exactly the moment any stale in-flight bookkeeping
         * (there should be none by the time an operator reaches for the retry button, since a
         * [BookingsUiState.Failed] screen draws no per-row actions at all) is safe to drop.
         */
        public fun refresh() {
            mutableState.update { BookingsUiState.Loading }
            busyBookingIds = emptySet()
            viewModelScope.launch {
                applyPendingResult(fetchQueue(), actionError = null)
            }
        }

        /** `26-49`: rejects a pending booking — [act] documents the one shape both veto actions share. */
        public fun reject(bookingId: String) {
            act(bookingId, api::rejectBooking)
        }

        /** `26-49`: cancels a pending booking — [act]'s own doc comment covers this and [reject] too. */
        public fun cancel(bookingId: String) {
            act(bookingId, api::cancelBooking)
        }

        /**
         * `26-49`: the one place both veto actions' shared shape lives — `docs/backlog/26-49-*.md`'s own
         * Scope items 2-3, ported from `CalendarQueuePage.tsx`'s own `act` verbatim rather than
         * re-derived:
         *
         * 1. **One deliberate tap, one server call.** A row already in [busyBookingIds] is a no-op —
         *    `CLAUDE.md` rule 5 ("no sync-over-async... every async API takes a token"), read from the
         *    client side as "a write already in flight for this row is never sent a second time".
         * 2. **Re-read the queue first, and only then show the failure.** Losing a race with the
         *    confirmation sweep — the booking this tap targeted already left the queue on its own — is
         *    an ordinary outcome, not a fault; a successful reload clears whatever error was showing, so
         *    setting the message before the reload would let that reload wipe the one sentence the
         *    operator needed (`CalendarQueuePage.tsx:166-186`'s own comment states the reasoning this
         *    ports). [applyPendingResult] folds the fresh rows and the (possibly `null`) error into one
         *    atomic state update, which keeps the two from ever being visible out of order.
         */
        private fun act(
            bookingId: String,
            action: suspend (String) -> BookingActionResult,
        ) {
            if (bookingId in busyBookingIds) return
            busyBookingIds = busyBookingIds + bookingId
            renderBusyBookingIds()

            viewModelScope.launch {
                val result = withContext(ioDispatcher) { action(bookingId) }
                busyBookingIds = busyBookingIds - bookingId

                val actionError =
                    when (result) {
                        BookingActionResult.Succeeded -> null
                        is BookingActionResult.Refused -> BookingActionErrorUi.ServerRefusal(result.detail)
                        is BookingActionResult.Failed -> BookingActionErrorUi.Unavailable(result.reason)
                    }
                applyPendingResult(fetchQueue(), actionError)
            }
        }

        /** The one read both [refresh] and [act] make — the queue, then the display-merge over it, so the
         * two callers cannot drift into one of them showing merged names and the other bare fallbacks. */
        private suspend fun fetchQueue(): PendingBookingsResult {
            val result = withContext(ioDispatcher) { api.fetchPendingQueue() }
            return if (result is PendingBookingsResult.Loaded) result.copy(bookings = mergeDisplayNames(result.bookings)) else result
        }

        /**
         * `26-163`/`adr/0184`: the identical chat-registry display-merge
         * [ago.chat.android.bookings.ContactsViewModel.mergeDisplayNames]'s own doc comment describes in
         * full, restated here for [PendingBooking] — a lookup miss or an unreachable [personsApi] leaves
         * [PendingBooking.customerDisplayName] at `null`, which
         * [ago.chat.android.core.domain.bookings.pendingBookingIdentity] already falls back from to the
         * masked phone (when the caller holds `customer:read`), then to a stated "no name" - never to the
         * hex person id. A failed merge is never surfaced as an error: the queue itself loaded, and a
         * nameless row is an honest, readable state, not a broken screen.
         */
        private suspend fun mergeDisplayNames(bookings: List<PendingBooking>): List<PendingBooking> {
            val personIds = bookings.map { it.customerId }.distinct()
            if (personIds.isEmpty()) return bookings

            val persons =
                when (val result = withContext(ioDispatcher) { personsApi.fetchPersons(personIds) }) {
                    is PersonsResult.Loaded -> result.persons
                    is PersonsResult.Failed -> return bookings
                }

            val namesByPersonId = persons.mapNotNull { person -> person.displayName?.let { name -> person.personId to name } }.toMap()
            if (namesByPersonId.isEmpty()) return bookings

            return bookings.map { booking ->
                namesByPersonId[booking.customerId]?.let { name -> booking.copy(customerDisplayName = name) } ?: booking
            }
        }

        /** Reflects [busyBookingIds] into the visible state immediately, before the write this tap
         * started has answered — the same "show the row disabled the instant the tap is registered"
         * responsiveness [ConversationListViewModel.claim]'s own `render(stale = ...)` call gives its
         * own `claimingIds`. A no-op if the screen is not currently [BookingsUiState.Loaded] (there is no
         * row to disable in any other state, so there is nothing for this to do). */
        private fun renderBusyBookingIds() {
            mutableState.update { current ->
                (current as? BookingsUiState.Loaded)?.copy(busyBookingIds = busyBookingIds) ?: current
            }
        }

        /** Turns one [PendingBookingsResult] into the matching [BookingsUiState], carrying [busyBookingIds]
         * and [actionError] along for the [BookingsUiState.Loaded] arm — the one function [refresh] and
         * [act] both funnel through, so the two never drift into building [BookingsUiState.Loaded]
         * slightly differently from one another. */
        private fun applyPendingResult(
            result: PendingBookingsResult,
            actionError: BookingActionErrorUi?,
        ) {
            mutableState.update {
                when (result) {
                    is PendingBookingsResult.Loaded ->
                        BookingsUiState.Loaded(
                            bookings = oldestDeadlineFirst(result.bookings),
                            busyBookingIds = busyBookingIds,
                            actionError = actionError,
                        )

                    PendingBookingsResult.NotConfigured -> BookingsUiState.NotConfigured
                    is PendingBookingsResult.Failed -> BookingsUiState.Failed(result.reason)
                }
            }
        }
    }
