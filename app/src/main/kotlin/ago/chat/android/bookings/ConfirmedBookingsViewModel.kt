package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingRevealSurface
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfirmedBooking
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.DayGroup
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.bookings.confirmedBookingsRange
import ago.chat.android.core.domain.bookings.confirmedBookingsStrip
import ago.chat.android.core.domain.bookings.groupByDayThenWorker
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
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * `26-51`: Утверждены's own state — one range read (`docs/backlog/26-51-*.md`'s own Scope item 2: "a
 * date range, not a day"), grouped once by [groupByDayThenWorker] and then sliced per selected day by
 * [ConfirmedBookingsUiState.Loaded.selectedDay] — never re-fetched on a day switch, since
 * [BookingsApi.fetchConfirmedBookings]'s own range already covers every day the strip can select.
 *
 * A sibling of [BookingsViewModel], not a merge into it: the two read different endpoints into
 * genuinely different shapes, and this class is only ever constructed at all for an operator holding
 * `customer:read` — [ago.chat.android.shell.AppShellScreen] computes that once, from the permission set
 * it already has in hand, and [BookingsRoute] only calls `hiltViewModel()` for this class inside that
 * branch, so an operator without the permission never triggers this read at all (`docs/backlog/26-51-*.md`'s
 * own Done-when: "does not see this segment at all" — extended here to "does not even ask the server
 * for it").
 *
 * `26-162`/`adr/0184`: [personsApi] is the identical display-merge
 * [ago.chat.android.bookings.ContactsViewModel]'s own doc comment describes for its own screen, restated
 * here because [api]'s own `ConfirmedBookingResponse` carries the identical bare `personId`-no-name
 * shape now. [mergeDisplayNames] is the one place this class closes that gap.
 */
@HiltViewModel
internal class ConfirmedBookingsViewModel
    @Inject
    constructor(
        private val api: BookingsApi,
        private val personsApi: PersonsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ConfirmedBookingsUiState>(ConfirmedBookingsUiState.Loading)
        val state: StateFlow<ConfirmedBookingsUiState> = mutableState.asStateFlow()

        /** `26-117`: which customers have a booking-detail reveal in flight right now — the identical
         * plain-instance-state shape [ContactsViewModel]'s own doc comment states for its own field of
         * the same name ("no lock needed" — this class is confined to the main thread, like every other
         * `ViewModel` here). */
        private var revealingCustomerIds: Set<String> = emptySet()

        /** `26-212`: the movable window's own anchor — [confirmedBookingsRange] is built around this on
         * every [refresh], and [onDatePicked] is the only thing that ever moves it. Defaults to today, in
         * the identical *UTC* calendar date [confirmedBookingsRange]'s own doc comment (via
         * `defaultConfirmedBookingsRange`) explains porting `ago-console`'s own `defaultRange` for, so the
         * screen opens on the identical seven-day window it always has until an operator picks a date. */
        private var anchorDate: LocalDate = LocalDate.now(ZoneOffset.UTC)

        init {
            refresh()
        }

        /** The initial load, and the retry action a [ConfirmedBookingsUiState.Failed] screen offers —
         * the identical "asking again is the whole of retry" shape [BookingsViewModel.refresh]'s own doc
         * comment states. Clears [revealingCustomerIds] the identical reason
         * [ContactsViewModel.refresh]'s own doc comment gives for clearing its own field of that name.
         * `26-212`: reads [anchorDate] rather than always re-deriving today, so a date-picker jump
         * ([onDatePicked]) and a plain retry both go through this one fetch — retrying after a jump
         * re-reads the *picked* window, never silently snaps back to today. */
        fun refresh() {
            mutableState.update { ConfirmedBookingsUiState.Loading }
            revealingCustomerIds = emptySet()
            viewModelScope.launch {
                val range = confirmedBookingsRange(anchorDate)
                val newState =
                    when (val result = withContext(ioDispatcher) { api.fetchConfirmedBookings(range.from, range.to) }) {
                        is ConfirmedBookingsResult.Loaded -> {
                            val days = groupByDayThenWorker(mergeDisplayNames(result.bookings))
                            ConfirmedBookingsUiState.Loaded(
                                days = days,
                                strip = confirmedBookingsStrip(range, days),
                                selectedDate = range.from,
                            )
                        }

                        ConfirmedBookingsResult.NotConfigured -> ConfirmedBookingsUiState.NotConfigured
                        is ConfirmedBookingsResult.Failed -> ConfirmedBookingsUiState.Failed(result.reason)
                    }
                mutableState.update { newState }
            }
        }

        /**
         * `26-162`/`adr/0184`: the identical chat-registry display-merge
         * [ago.chat.android.bookings.ContactsViewModel.mergeDisplayNames]'s own doc comment describes in
         * full, restated here for [ConfirmedBooking] instead of [ago.chat.android.core.domain.bookings.Contact] —
         * a lookup miss or an unreachable [personsApi] leaves [ConfirmedBooking.customerDisplayName] at
         * `null`, which [confirmedBookingIdentity] already falls back from to the masked phone, then to
         * [ago.chat.android.core.domain.bookings.BookingIdentity.NoName].
         */
        private suspend fun mergeDisplayNames(bookings: List<ConfirmedBooking>): List<ConfirmedBooking> {
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

        /** The date strip's own click handler — a pure selection change over data already in hand, no
         * second network call (this class's own doc comment on why the range read already covers every
         * selectable day). A no-op while [state] is not [ConfirmedBookingsUiState.Loaded] (there is no
         * strip to have been clicked yet). */
        fun onDaySelected(date: String) {
            mutableState.update { current ->
                if (current is ConfirmedBookingsUiState.Loaded) current.copy(selectedDate = date) else current
            }
        }

        /**
         * `26-212`: the month/year header's own date-picker jump — moves [anchorDate] to [date] and
         * re-fetches an entirely new range around it, unlike [onDaySelected] above which only ever
         * re-slices a range already in hand. A malformed [date] (there should be no way for the Material3
         * picker this is wired to ever produce one) leaves [anchorDate] untouched rather than crashing the
         * screen — the identical "never invented, never fails the screen" posture
         * [ConfirmedBookingsViewModel.mergeDisplayNames]'s own doc comment states for an unreachable
         * [personsApi].
         */
        fun onDatePicked(date: String) {
            val picked = runCatching { LocalDate.parse(date) }.getOrNull() ?: return
            anchorDate = picked
            refresh()
        }

        /**
         * `26-233`: the «Сегодня» control — jumps [anchorDate] back to today's own UTC calendar date and
         * re-fetches around it, the identical [refresh]-through-[anchorDate] shape [onDatePicked] already
         * uses for a picked date. A full [ConfirmedBookingsUiState.Loading] pass, unlike
         * [onPullToLoadWeek] below: this is reached "from anywhere" (the ticket's own wording) rather than
         * mid-gesture on a strip already on screen, so there is no existing day list worth keeping visible
         * while it loads — the same full-reload shape a date-picker jump already takes.
         */
        fun jumpToToday() {
            anchorDate = LocalDate.now(ZoneOffset.UTC)
            refresh()
        }

        /**
         * `26-233`: the day strip's own rubber-band edge-pull — [ConfirmedBookingsScreen.kt]'s
         * `ConfirmedDateStrip` calls this once a pull past either edge crosses its trigger distance.
         * Unlike [onDatePicked]/[jumpToToday], this never swaps the whole screen to
         * [ConfirmedBookingsUiState.Loading]: the operator is mid-gesture on a strip that already shows a
         * week of real data, so [state] stays [ConfirmedBookingsUiState.Loaded] throughout, with
         * [ConfirmedBookingsUiState.Loaded.edgeLoading] naming which side is in flight for the strip's own
         * in-lane spinner to read. A no-op while [state] is not already [ConfirmedBookingsUiState.Loaded]
         * (there is no strip to have been pulled) or while an edge load is already in flight (a second
         * pull on the same side, or the far side, before the first answers back — the identical
         * one-in-flight-at-a-time guard [reveal] already applies per customer, applied here to the strip
         * as a whole since it has only one edge loading at once).
         *
         * The requested week is fetched *before* [anchorDate] moves — a [ConfirmedBookingsResult.Failed]/
         * [ConfirmedBookingsResult.NotConfigured] answer leaves [anchorDate] exactly where the strip still
         * visibly is, so a failed pull can be retried by pulling again rather than leaving the anchor
         * pointing at a week the screen never actually loaded.
         */
        fun onPullToLoadWeek(direction: DateStripEdgeLoad) {
            val loaded = mutableState.value as? ConfirmedBookingsUiState.Loaded ?: return
            if (loaded.edgeLoading != null) return
            val requestedAnchor =
                when (direction) {
                    DateStripEdgeLoad.Previous -> anchorDate.minusDays(WEEK_SPAN_DAYS)
                    DateStripEdgeLoad.Next -> anchorDate.plusDays(WEEK_SPAN_DAYS)
                }
            mutableState.update { current ->
                if (current is ConfirmedBookingsUiState.Loaded) current.copy(edgeLoading = direction) else current
            }
            viewModelScope.launch {
                val range = confirmedBookingsRange(requestedAnchor)
                when (val result = withContext(ioDispatcher) { api.fetchConfirmedBookings(range.from, range.to) }) {
                    is ConfirmedBookingsResult.Loaded -> {
                        anchorDate = requestedAnchor
                        val days = groupByDayThenWorker(mergeDisplayNames(result.bookings))
                        // `26-233`: "snap to the new week's first day" - `selectedDate = range.from` is
                        // the identical rule the initial today-anchored load and [onDatePicked] both
                        // already follow; a brand-new [ConfirmedBookingsUiState.Loaded] rather than
                        // `loaded.copy(...)`, since `days`/`strip`/`selectedDate` all replace the prior
                        // week's own values wholesale, not merge with them.
                        mutableState.update {
                            ConfirmedBookingsUiState.Loaded(
                                days = days,
                                strip = confirmedBookingsStrip(range, days),
                                selectedDate = range.from,
                            )
                        }
                    }

                    // A deployment cannot genuinely stop running AGO Calendar mid-session, but the type
                    // still has to be exhausted - kept as a graceful "the pull didn't work", not a crash
                    // or a fabricated empty week.
                    ConfirmedBookingsResult.NotConfigured ->
                        mutableState.update { current ->
                            if (current is ConfirmedBookingsUiState.Loaded) {
                                current.copy(
                                    edgeLoading = null,
                                    actionError = BookingActionErrorUi.Unavailable(BookingsQueueFailure.Unexpected),
                                )
                            } else {
                                current
                            }
                        }

                    is ConfirmedBookingsResult.Failed ->
                        mutableState.update { current ->
                            if (current is ConfirmedBookingsUiState.Loaded) {
                                current.copy(edgeLoading = null, actionError = BookingActionErrorUi.Unavailable(result.reason))
                            } else {
                                current
                            }
                        }
                }
            }
        }

        /**
         * `26-117`: the booking-detail sheet's own «Показать» — `docs/backlog/26-117-*.md`'s own hard
         * requirement 9 reusing the identical audited reveal `26-53` already established, keyed here by
         * [BookingRevealSurface.ANDROID_BOOKINGS] rather than [BookingRevealSurface.ANDROID_CONTACTS] so
         * the audit trail can tell the two screens' own reveals apart (`BookingRevealSurface`'s own doc
         * comment). The identical one-reveal-per-customer-at-a-time, match-by-customerId-not-row shape
         * [ContactsViewModel.reveal]'s own doc comment states in full — [replacePhone] below is the one
         * difference: a confirmed booking's own rows are nested under [DayGroup]/[WorkerGroup] rather
         * than sitting in a flat list, so unmasking in place means mapping through both levels.
         */
        fun reveal(customerId: String) {
            if (customerId in revealingCustomerIds) return
            val loaded = mutableState.value as? ConfirmedBookingsUiState.Loaded ?: return
            revealingCustomerIds = revealingCustomerIds + customerId
            mutableState.update { loaded.copy(revealingCustomerIds = revealingCustomerIds, actionError = null) }

            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.revealCustomerPhone(customerId, BookingRevealSurface.ANDROID_BOOKINGS) }
                revealingCustomerIds = revealingCustomerIds - customerId

                mutableState.update { current ->
                    val currentLoaded = current as? ConfirmedBookingsUiState.Loaded ?: return@update current
                    when (result) {
                        is RevealPhoneResult.Revealed ->
                            currentLoaded.copy(
                                days = replacePhone(currentLoaded.days, customerId, result.phone),
                                revealingCustomerIds = revealingCustomerIds,
                                actionError = null,
                            )

                        is RevealPhoneResult.Refused ->
                            currentLoaded.copy(
                                revealingCustomerIds = revealingCustomerIds,
                                actionError = BookingActionErrorUi.ServerRefusal(result.detail),
                            )

                        is RevealPhoneResult.Failed ->
                            currentLoaded.copy(
                                revealingCustomerIds = revealingCustomerIds,
                                actionError = BookingActionErrorUi.Unavailable(result.reason),
                            )
                    }
                }
            }
        }

        /** [reveal]'s own in-place unmask, across every [DayGroup]/[WorkerGroup] this range read holds —
         * a customer with several confirmed bookings (different masters, different days) has every one
         * of those rows unmasked together, the identical "match by customerId, not row" rule
         * [ContactsViewModel.reveal]'s own doc comment states, extended to two nesting levels. */
        private fun replacePhone(
            days: List<DayGroup>,
            customerId: String,
            phone: String,
        ): List<DayGroup> =
            days.map { day ->
                day.copy(
                    workers =
                        day.workers.map { worker ->
                            worker.copy(
                                rows =
                                    worker.rows.map { row ->
                                        if (row.customerId == customerId) row.copy(phone = phone, masked = false) else row
                                    },
                            )
                        },
                )
            }
    }

/** `26-233`: one week, in days - the span [onPullToLoadWeek] shifts [ConfirmedBookingsViewModel.anchorDate]
 * by so two consecutively-loaded weeks sit back-to-back with no gap and no overlap. Not
 * `ago.chat.android.core.domain.bookings.ConfirmedBookingsRange`'s own `RANGE_HORIZON_DAYS` (6, the last
 * day *within* a window measured from its first) - this is the window's own full width, one more than
 * that, the distance from one window's first day to the next window's first day. */
private const val WEEK_SPAN_DAYS = 7L
