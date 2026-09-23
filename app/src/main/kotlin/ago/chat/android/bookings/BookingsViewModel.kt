package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.PendingBookingsResult
import ago.chat.android.core.domain.bookings.oldestDeadlineFirst
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
 */
@HiltViewModel
public class BookingsViewModel
    @Inject
    constructor(
        private val api: BookingsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<BookingsUiState>(BookingsUiState.Loading)
        public val state: StateFlow<BookingsUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, and the retry action a [BookingsUiState.Failed] screen offers — the same
         * function for both, since a retry is simply "ask again", never a second code path
         * (`ClaimResult.Refused`'s own doc comment states the identical "never a silent retry" rule for
         * the opposite case; here the *operator* decides to retry, explicitly, every time). */
        public fun refresh() {
            mutableState.update { BookingsUiState.Loading }
            viewModelScope.launch {
                mutableState.update {
                    when (val result = withContext(ioDispatcher) { api.fetchPendingQueue() }) {
                        is PendingBookingsResult.Loaded -> BookingsUiState.Loaded(oldestDeadlineFirst(result.bookings))
                        PendingBookingsResult.NotConfigured -> BookingsUiState.NotConfigured
                        is PendingBookingsResult.Failed -> BookingsUiState.Failed(result.reason)
                    }
                }
            }
        }
    }
