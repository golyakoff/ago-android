package ago.chat.android.bookings

import ago.chat.android.core.domain.readiness.BookingReadinessApi
import ago.chat.android.core.domain.readiness.BookingReadinessResult
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
 * `26-164`: Записи's own «Готовность» hub entry — a single read over [BookingReadinessApi], with no write
 * of any kind. Constructed only for an operator holding `calendar:configure`
 * ([ago.chat.android.shell.AppShellScreen] computes the gate once and [BookingsRoute] calls
 * `hiltViewModel()` only inside that branch), the identical "does not even ask the server for it" gate
 * [MastersViewModel]'s own doc comment states.
 *
 * **"Re-read on open + retry only" (`docs/design/26-154-*.md`'s own accepted Q4, no pull-to-refresh).**
 * [refresh] is the one method this class exposes beyond construction — [init] calls it once so the state
 * is useful the moment this view model exists, [BookingsRoute]'s own `LaunchedEffect` on `activeConfigTab`
 * calls it again every time the operator actually opens the «Готовность» screen (this view model persists
 * across that screen closing and reopening, scoped to the whole Записи route, not to the screen itself —
 * so without that explicit re-trigger a reopen would show whatever this class last saw, not a fresh read),
 * and [ReadinessBody]'s own `RefusalBody` retry calls it a third time. All three are the identical
 * request, never three different ones.
 */
@HiltViewModel
internal class ReadinessViewModel
    @Inject
    constructor(
        private val api: BookingReadinessApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ReadinessUiState>(ReadinessUiState.Loading)
        val state: StateFlow<ReadinessUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, every later re-open of the screen, and the retry a [ReadinessUiState.Failed]
         * screen offers — all three the identical request (this class's own doc comment). */
        fun refresh() {
            mutableState.update { ReadinessUiState.Loading }
            viewModelScope.launch {
                mutableState.update {
                    when (val result = withContext(ioDispatcher) { api.fetchReadiness() }) {
                        is BookingReadinessResult.Loaded -> ReadinessUiState.Loaded(result.calendars)
                        BookingReadinessResult.NotConfigured -> ReadinessUiState.NotConfigured
                        is BookingReadinessResult.Failed -> ReadinessUiState.Failed(result.reason)
                    }
                }
            }
        }
    }
