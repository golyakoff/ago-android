package ago.chat.android.documents

import ago.chat.android.R
import ago.chat.android.core.domain.consent.ConsentDocumentBounds
import ago.chat.android.ui.icons.AgoIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * `26-226` (`docs/design/tenant-consent-android.md` §3.4): the full-screen publish editor - blank-start,
 * always a *new* version, never an edit-in-place - the identical shape
 * [ago.chat.android.automation.CannedResponsesScreen]'s own private `CannedResponseEditorScreen`
 * establishes for a title+body form, with two additions this form's own stakes justify: a paste
 * shortcut (a prepared legal text is the real mobile path here, `scope-inventory.md` §9) and a
 * confirm-before-publish dialog, since publishing is irreversible and immediately visitor-facing -
 * unlike a canned response, which only a colleague ever sees.
 *
 * **Back (system or the app bar's own arrow) guards a non-empty draft.** A composed legal text is worth
 * protecting, unlike the canned-response editor's transient one-shot input
 * (`tenant-consent-android.md` §4.3) - [BackHandler] and the app bar's own icon both route through
 * [requestBack] rather than calling [onBack] directly, so neither path can bypass the discard confirm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConsentPublishEditorScreen(
    purposeTitle: String,
    publishing: Boolean,
    error: ConsentPublishActionError?,
    onPublish: (title: String, body: String) -> Unit,
    onBack: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var discarding by rememberSaveable { mutableStateOf(false) }

    val requestBack: () -> Unit = {
        if (title.isNotBlank() || body.isNotBlank()) discarding = true else onBack()
    }
    BackHandler(onBack = requestBack)

    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = purposeTitle) },
                    navigationIcon = {
                        IconButton(onClick = requestBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
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
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.consent_publish_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= ConsentDocumentBounds.MAX_TITLE_LENGTH) title = it },
                    label = { Text(text = stringResource(R.string.consent_publish_title_label)) },
                    supportingText = { Text(text = "${title.length}/${ConsentDocumentBounds.MAX_TITLE_LENGTH}") },
                    singleLine = true,
                    enabled = !publishing,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = body,
                    onValueChange = { if (it.length <= ConsentDocumentBounds.MAX_BODY_LENGTH) body = it },
                    label = { Text(text = stringResource(R.string.consent_publish_body_label)) },
                    supportingText = { Text(text = "${body.length}/${ConsentDocumentBounds.MAX_BODY_LENGTH}") },
                    minLines = 8,
                    enabled = !publishing,
                    modifier = Modifier.fillMaxWidth(),
                )

                TextButton(
                    onClick = {
                        scope.launch {
                            val pasted =
                                clipboard
                                    .getClipEntry()
                                    ?.clipData
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                            if (!pasted.isNullOrEmpty()) {
                                body = (body + pasted).take(ConsentDocumentBounds.MAX_BODY_LENGTH)
                            }
                        }
                    },
                    enabled = !publishing,
                ) {
                    Text(text = stringResource(R.string.consent_publish_paste_action))
                }

                error?.let { InlineAlert(text = consentPublishActionErrorText(it)) }

                Button(
                    onClick = {
                        if (validateConsentPublishDraft(title, body) == null) {
                            confirming = true
                        } else {
                            // An invalid draft never opens the confirm dialog - the click still reaches
                            // the view model so its own courtesy check sets the identical
                            // `ConsentPublishActionError.Invalid` this screen already knows how to render.
                            onPublish(title, body)
                        }
                    },
                    enabled = !publishing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text =
                            stringResource(
                                if (publishing) R.string.consent_publish_action_publishing else R.string.consent_publish_action_save,
                            ),
                    )
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(text = stringResource(R.string.consent_publish_confirm_title)) },
            text = { Text(text = stringResource(R.string.consent_publish_confirm_message, purposeTitle)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onPublish(title, body)
                }) {
                    Text(text = stringResource(R.string.consent_publish_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (discarding) {
        AlertDialog(
            onDismissRequest = { discarding = false },
            title = { Text(text = stringResource(R.string.consent_publish_discard_title)) },
            text = { Text(text = stringResource(R.string.consent_publish_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    discarding = false
                    onBack()
                }) {
                    Text(text = stringResource(R.string.consent_publish_discard_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { discarding = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** An inline banner for a courtesy-validation problem, a conflict, a server refusal, or a transport
 * failure - the identical tonal-danger-surface shape
 * [ago.chat.android.automation.CannedResponsesScreen]'s own private `InlineAlert` already establishes,
 * restated here for the same "neither file imports composables from the other" reason that file's own
 * doc comment states. */
@Composable
private fun InlineAlert(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}
