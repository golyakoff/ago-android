package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.bookings.ContactsResult
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
 * `26-52`: Записи's own «Клиенты» state — one plain read, no range and no polling, the identical
 * one-call shape [BookingsViewModel] already establishes for Ожидают, restated here rather than merged
 * into either sibling class because this reads a third, genuinely different endpoint into a third
 * shape ([ago.chat.android.core.domain.bookings.Contact], not a booking).
 *
 * A sibling of [BookingsViewModel]/[ConfirmedBookingsViewModel], not a merge into either: this class is
 * only ever constructed at all for an operator holding `calendar:configure` or `customer:read`
 * ([ago.chat.android.shell.AppShellScreen] computes that once, from the permission set it already has
 * in hand, and [BookingsRoute] only calls `hiltViewModel()` for this class inside that branch) — the
 * identical "does not even ask the server for it" discipline [ConfirmedBookingsViewModel]'s own doc
 * comment states for its own gate.
 */
@HiltViewModel
internal class ContactsViewModel
    @Inject
    constructor(
        private val api: BookingsApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ContactsUiState>(ContactsUiState.Loading)
        val state: StateFlow<ContactsUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, and the retry action a [ContactsUiState.Failed] screen offers — the
         * identical "asking again is the whole of retry" shape [BookingsViewModel.refresh]'s own doc
         * comment states. */
        fun refresh() {
            mutableState.update { ContactsUiState.Loading }
            viewModelScope.launch {
                mutableState.update {
                    when (val result = withContext(ioDispatcher) { api.fetchContacts() }) {
                        is ContactsResult.Loaded -> ContactsUiState.Loaded(result.contacts)
                        ContactsResult.NotConfigured -> ContactsUiState.NotConfigured
                        is ContactsResult.Failed -> ContactsUiState.Failed(result.reason)
                    }
                }
            }
        }
    }
