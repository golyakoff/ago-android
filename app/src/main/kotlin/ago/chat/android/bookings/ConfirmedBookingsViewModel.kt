package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.ConfirmedBookingsResult
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

        init {
            refresh()
        }

        /** The initial load, and the retry action a [ConfirmedBookingsUiState.Failed] screen offers —
         * the identical "asking again is the whole of retry" shape [BookingsViewModel.refresh]'s own doc
         * comment states. */
        fun refresh() {
            mutableState.update { ConfirmedBookingsUiState.Loading }
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
    }
