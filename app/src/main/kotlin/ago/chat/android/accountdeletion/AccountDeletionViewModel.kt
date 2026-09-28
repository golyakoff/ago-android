package ago.chat.android.accountdeletion

import ago.chat.android.core.domain.accountdeletion.AccountDeletionApi
import ago.chat.android.core.domain.accountdeletion.EraseAccountResult
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
 * `26-252`: the «Удалить аккаунт» screen's own state machine — a warning panel, a deliberate confirmation, and
 * the terminal "erasing" state a successful `202` produces. The identical `MutableStateFlow`/`viewModelScope`
 * shape [ago.chat.android.siteexport.SiteExportViewModel] establishes, minus the read (this screen has no list
 * to load — it opens straight into its warning panel, so there is no `init`/`refresh`).
 *
 * ## The confirmation gate — the whole point of this item
 *
 * Deleting the account is irreversible, so the erase call must be reachable **only** through an explicit,
 * deliberate confirm. Three methods enforce that:
 *
 * - [askConfirmation] opens the dialog. It makes **no** API call — merely wanting to confirm deletes nothing.
 * - [dismissConfirmation] closes the dialog. Again no API call.
 * - [confirmDeletion] is the only method that ever calls [AccountDeletionApi.eraseAccount], and it is a
 *   **no-op unless the dialog is currently open** ([AccountDeletionUiState.Ready.confirming]). So the erase
 *   cannot fire from a fresh screen, from a dismissed dialog, or from any state other than an explicitly
 *   opened confirmation — which is exactly what the gate test asserts.
 *
 * The alternative — a single `deleteAccount()` the button calls directly — would delete the moment anything
 * invoked it, leaving "did the user really confirm" a property of the UI alone, untestable without Compose.
 * Guarding on `confirming` here puts the deliberate-confirm invariant in the layer a unit test can pin down.
 *
 * ## Why no poll, unlike the console
 *
 * The console's `AccountDeletionPage` polls `operators/me` after the `202` and signs out once it observes the
 * `403`. This screen ends in [AccountDeletionUiState.Erasing] and hands the operator a deliberate «Выйти»
 * action instead: a phone screen is a drill-in the operator can simply leave, so a background poll that force
 * signs them out mid-read would be a heavier, more surprising mechanism than the honest "the account is being
 * deleted; sign out" the terminal panel states. Sign-out itself is a shell-level navigation action, so it
 * lives on the screen's `onSignOut` callback, never in this view model.
 */
@HiltViewModel
public class AccountDeletionViewModel
    @Inject
    constructor(
        private val api: AccountDeletionApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<AccountDeletionUiState>(AccountDeletionUiState.Ready())
        public val state: StateFlow<AccountDeletionUiState> = mutableState.asStateFlow()

        /** Opens the confirmation dialog and clears any prior inline error/refusal — no API call. */
        public fun askConfirmation() {
            mutableState.update { current ->
                (current as? AccountDeletionUiState.Ready)?.copy(
                    confirming = true,
                    submitError = null,
                    refusal = null,
                ) ?: current
            }
        }

        /** Closes the confirmation dialog — no API call. A no-op while a request is already in flight. */
        public fun dismissConfirmation() {
            mutableState.update { current ->
                val ready = current as? AccountDeletionUiState.Ready ?: return@update current
                if (ready.submitting) ready else ready.copy(confirming = false)
            }
        }

        /**
         * Fires the irreversible erase — **only** when the confirmation dialog is open and no request is
         * already in flight. On [EraseAccountResult.Requested] the screen becomes
         * [AccountDeletionUiState.Erasing]; a refusal or transport failure returns to the warning panel with
         * the failure surfaced inline and the dialog dismissed.
         */
        public fun confirmDeletion() {
            val ready = mutableState.value as? AccountDeletionUiState.Ready ?: return
            // The gate: without an open confirmation there is no deliberate confirm, so there is no erase.
            if (!ready.confirming || ready.submitting) return

            mutableState.update { current ->
                (current as? AccountDeletionUiState.Ready)?.copy(submitting = true) ?: current
            }

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.eraseAccount() }) {
                    EraseAccountResult.Requested -> mutableState.update { AccountDeletionUiState.Erasing }

                    is EraseAccountResult.Refused ->
                        mutableState.update { current ->
                            (current as? AccountDeletionUiState.Ready)?.copy(
                                confirming = false,
                                submitting = false,
                                refusal = result.detail,
                            ) ?: current
                        }

                    is EraseAccountResult.Failed ->
                        mutableState.update { current ->
                            (current as? AccountDeletionUiState.Ready)?.copy(
                                confirming = false,
                                submitting = false,
                                submitError = result.reason,
                            ) ?: current
                        }
                }
            }
        }
    }
