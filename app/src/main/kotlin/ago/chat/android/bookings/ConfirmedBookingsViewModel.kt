package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingRevealSurface
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
import ago.chat.android.core.domain.bookings.DayGroup
import ago.chat.android.core.domain.bookings.RevealPhoneResult
import ago.chat.android.core.domain.bookings.confirmedBookingsStrip
import ago.chat.android.core.domain.bookings.defaultConfirmedBookingsRange
import ago.chat.android.core.domain.bookings.groupByDayThenWorker
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
 */
@HiltViewModel
internal class ConfirmedBookingsViewModel
    @Inject
    constructor(
        private val api: BookingsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ConfirmedBookingsUiState>(ConfirmedBookingsUiState.Loading)
        val state: StateFlow<ConfirmedBookingsUiState> = mutableState.asStateFlow()

        /** `26-117`: which customers have a booking-detail reveal in flight right now — the identical
         * plain-instance-state shape [ContactsViewModel]'s own doc comment states for its own field of
         * the same name ("no lock needed" — this class is confined to the main thread, like every other
         * `ViewModel` here). */
        private var revealingCustomerIds: Set<String> = emptySet()

        init {
            refresh()
        }

        /** The initial load, and the retry action a [ConfirmedBookingsUiState.Failed] screen offers —
         * the identical "asking again is the whole of retry" shape [BookingsViewModel.refresh]'s own doc
         * comment states. Clears [revealingCustomerIds] the identical reason
         * [ContactsViewModel.refresh]'s own doc comment gives for clearing its own field of that name. */
        fun refresh() {
            mutableState.update { ConfirmedBookingsUiState.Loading }
            revealingCustomerIds = emptySet()
            viewModelScope.launch {
                // `ago-console`'s own `defaultRange` reads a bare `new Date()`, i.e. the *UTC* calendar
                // date - `defaultConfirmedBookingsRange`'s own doc comment on why this ports that
                // faithfully rather than substituting a device-local date this class has no more reason
                // to trust than the console's own browser-local one.
                val today = LocalDate.now(ZoneOffset.UTC)
                val range = defaultConfirmedBookingsRange(today)
                mutableState.update {
                    when (val result = withContext(ioDispatcher) { api.fetchConfirmedBookings(range.from, range.to) }) {
                        is ConfirmedBookingsResult.Loaded -> {
                            val days = groupByDayThenWorker(result.bookings)
                            ConfirmedBookingsUiState.Loaded(
                                days = days,
                                strip = confirmedBookingsStrip(range, days),
                                selectedDate = range.from,
                            )
                        }

                        ConfirmedBookingsResult.NotConfigured -> ConfirmedBookingsUiState.NotConfigured
                        is ConfirmedBookingsResult.Failed -> ConfirmedBookingsUiState.Failed(result.reason)
                    }
                }
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
