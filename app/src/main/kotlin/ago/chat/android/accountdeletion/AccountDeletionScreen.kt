package ago.chat.android.accountdeletion

import ago.chat.android.R
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * `26-252` (`ago-console`'s own `AccountDeletionPage`, ported): Ещё → Администрирование → «Удалить аккаунт» —
 * a warning screen whose single, irreversible action deletes the whole account, behind a deliberate
 * confirmation dialog. Obtains its own [AccountDeletionViewModel] via [hiltViewModel], the identical wiring
 * [ago.chat.android.siteexport.SiteExportRoute] establishes for its own Ещё drill-in.
 *
 * `MoreScreen` composes this row only for an operator holding `site:erase` (hide-not-disable), the same
 * permission `ago-console`'s own `AccountDeletionPage` gates `/account/delete` on — so the gate lives one
 * level up in the row list, not as an in-screen check, exactly as every other real Ещё row gates itself.
 * Note this is `site:erase`, **not** the `site:configure` most Администрирование rows gate on.
 *
 * [onSignOut] is threaded down from the shell because the terminal [AccountDeletionUiState.Erasing] state
 * ends the session — the console signs out automatically after its poll; this screen hands the operator a
 * deliberate «Выйти» action instead (see [AccountDeletionViewModel]'s own doc comment for why no poll).
 */
@Composable
internal fun AccountDeletionRoute(
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: AccountDeletionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountDeletionScreen(
        state = state,
        onAskConfirmation = viewModel::askConfirmation,
        onDismissConfirmation = viewModel::dismissConfirmation,
        onConfirmDeletion = viewModel::confirmDeletion,
        onSignOut = onSignOut,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountDeletionScreen(
    state: AccountDeletionUiState,
    onAskConfirmation: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onConfirmDeletion: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.account_deletion_title)) },
                    navigationIcon = {
                        // No back arrow once erasing has started — there is nothing left on this screen to go
                        // back to and the only sensible next action is signing out, the same "the panel is
                        // replaced, not navigated away from" the console's own `erasing` branch draws.
                        if (state is AccountDeletionUiState.Ready) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = AgoIcons.Back,
                                    contentDescription = stringResource(R.string.action_back),
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                when (state) {
                    is AccountDeletionUiState.Ready ->
                        WarningPanel(state = state, onAskConfirmation = onAskConfirmation)

                    AccountDeletionUiState.Erasing -> ErasingPanel(onSignOut = onSignOut)
                }
            }
        }

        val ready = state as? AccountDeletionUiState.Ready
        if (ready != null && ready.confirming) {
            ConfirmDeletionDialog(
                submitting = ready.submitting,
                onConfirm = onConfirmDeletion,
                onDismiss = onDismissConfirmation,
            )
        }
    }
}

/** The warning panel and the danger action that opens the confirmation — the console's own `Panel` plus its
 * `variant="danger"` button, with the same "there is no confirmation beyond this one" wording. The inline
 * refusal/error appear here, above the button, exactly as the console draws its `submitError` `Alert`. */
@Composable
private fun WarningPanel(
    state: AccountDeletionUiState.Ready,
    onAskConfirmation: () -> Unit,
) {
    Text(
        text = stringResource(R.string.account_deletion_description),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = stringResource(R.string.account_deletion_warning_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
    )

    state.refusal?.let { detail ->
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
    state.submitError?.let { failure ->
        Text(
            text = networkFailureText(failure),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }

    Button(
        onClick = onAskConfirmation,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
        Text(text = stringResource(R.string.account_deletion_button))
    }
}

/** The terminal "erasing has started" state — the console's own `erasing` panel: the account is being
 * deleted, and the one action left is signing out. */
@Composable
private fun ErasingPanel(onSignOut: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.account_deletion_in_progress_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.account_deletion_in_progress_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
        Button(
            onClick = onSignOut,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            Text(text = stringResource(R.string.account_deletion_sign_out_action))
        }
    }
}

/** The deliberate confirmation — the console's own native `<dialog>` (`adr/0030`), the identical destructive
 * [AlertDialog] shape [ago.chat.android.storage.StorageScreen]'s own bulk-delete confirm establishes. The
 * confirm button carries the erase's own in-flight spinner; both buttons disable while it runs so a second
 * tap cannot fire a second erase. */
@Composable
private fun ConfirmDeletionDialog(
    submitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(text = stringResource(R.string.account_deletion_dialog_title)) },
        text = { Text(text = stringResource(R.string.account_deletion_dialog_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !submitting) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.account_deletion_confirm_button),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}
