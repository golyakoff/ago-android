package ago.chat.android.automation

import ago.chat.android.R
import ago.chat.android.bookings.EmptyBody
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.tags.Tag
import ago.chat.android.core.domain.tags.TagBounds
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * `26-225` (`docs/design/tenant-canned-tags-android.md` §2): Автоматизация → «Метки» - managing this
 * site's own tag vocabulary, the other half of `26-115`'s conversation-tag sheet
 * ([ago.chat.android.core.domain.tags.ConversationTagsApi] applies/removes; this screen creates, renames
 * and deletes the vocabulary entries themselves - §2.5). Obtains its own [TagsViewModel] via
 * [hiltViewModel] - the identical wiring [ago.chat.android.automation.CannedResponsesRoute] already
 * establishes for a drill-in [ago.chat.android.shell.MoreScreen] composes only for an operator holding
 * `site:configure`.
 */
@Composable
internal fun TagsRoute(
    onBack: () -> Unit,
    viewModel: TagsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TagsScreen(
        state = state,
        onRetry = viewModel::refresh,
        onCreate = viewModel::create,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onBack = onBack,
    )
}

/**
 * The stateless half - a list, a FAB, and a **dialog** editor rather than
 * [ago.chat.android.automation.CannedResponsesScreen]'s own full-screen one: a tag is a single short name
 * (`docs/design/tenant-canned-tags-android.md` §2.4), so a create/rename dialog is the right weight,
 * matching `TagsPage`'s own in-place rename and [ago.chat.android.schedule.WorkingHoursScreen]'s dialog
 * precedent.
 *
 * **Each mutation is its own call, followed by a re-fetch** - there is no whole-list write here, unlike
 * [ago.chat.android.automation.CannedResponsesScreen]; this composable never assembles a request itself,
 * it only tells [TagsViewModel] which id (if any) and which name.
 *
 * **Which dialog is open is transient composition state**, via [remember] - not a [TagsUiState] arm, the
 * identical discipline [ago.chat.android.schedule.WorkingHoursScreen]'s own edit dialog already follows.
 * A successful create/rename bumps [TagsUiState.Loaded.savedTick], this screen's own signal to close the
 * create/rename dialog; a [TagsActionError.ServerRefusal] (`Tag.AlreadyExists`) never bumps it, so the
 * dialog stays open and renders the refusal inline. The delete confirm dismisses on the tap itself,
 * before the result is known - the identical shape
 * [ago.chat.android.automation.CannedResponsesScreen]'s own delete confirm already uses.
 */
@Composable
internal fun TagsScreen(
    state: TagsUiState,
    onRetry: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (tagId: String, name: String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<TagEditorTarget?>(null) }
    var confirmingDelete by remember { mutableStateOf<Tag?>(null) }

    val savedTick = (state as? TagsUiState.Loaded)?.savedTick ?: 0
    LaunchedEffect(savedTick) {
        if (savedTick > 0) editing = null
    }

    TagsListScreen(
        state = state,
        onRetry = onRetry,
        onAdd = { editing = TagEditorTarget.New },
        onEdit = { tag -> editing = TagEditorTarget.Renaming(tag) },
        onDeleteRequest = { tag -> confirmingDelete = tag },
        onBack = onBack,
    )

    editing?.let { target ->
        TagEditorDialog(
            initialName = (target as? TagEditorTarget.Renaming)?.tag?.name.orEmpty(),
            isRename = target is TagEditorTarget.Renaming,
            busy = (state as? TagsUiState.Loaded)?.busy ?: false,
            error = (state as? TagsUiState.Loaded)?.error,
            onDismiss = { editing = null },
            onSave = { name ->
                when (target) {
                    TagEditorTarget.New -> onCreate(name)
                    is TagEditorTarget.Renaming -> onRename(target.tag.id, name)
                }
            },
        )
    }

    confirmingDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text(text = stringResource(R.string.automation_tags_delete_title)) },
            text = { Text(text = stringResource(R.string.automation_tags_delete_message, tag.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = null
                    onDelete(tag.id)
                }) {
                    Text(text = stringResource(R.string.automation_tags_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Which dialog [TagsScreen] currently shows, if any - a new tag, or an existing row's own [Tag] being
 * renamed. `private`: this screen's own transient composition state, never read outside it. */
private sealed interface TagEditorTarget {
    data object New : TagEditorTarget

    data class Renaming(
        val tag: Tag,
    ) : TagEditorTarget
}

/** The list screen - Route/Screen split, back arrow, no
 * [ago.chat.android.ui.components.AccountAvatarAction] (a drill-in), the identical shape
 * [ago.chat.android.automation.CannedResponsesScreen]'s own list screen already establishes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagsListScreen(
    state: TagsUiState,
    onRetry: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Tag) -> Unit,
    onDeleteRequest: (Tag) -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.more_automation_tags_row)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
            floatingActionButton = {
                if (state is TagsUiState.Loaded) {
                    FloatingActionButton(onClick = onAdd) {
                        Icon(imageVector = AgoIcons.Plus, contentDescription = stringResource(R.string.automation_tags_add_action))
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    TagsUiState.Loading -> LoadingBody()
                    is TagsUiState.Failed -> TagsFailedBody(reason = state.reason, onRetry = onRetry)
                    is TagsUiState.Loaded ->
                        TagsLoadedBody(
                            state = state,
                            onEdit = onEdit,
                            onDeleteRequest = onDeleteRequest,
                        )
                }
            }
        }
    }
}

/** The read itself failed - the identical "message, retry" shape
 * [ago.chat.android.automation.CannedResponsesScreen]'s own private failed body already establishes,
 * restated here since neither file imports composables from the other. */
@Composable
private fun TagsFailedBody(
    reason: NetworkFailure,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = networkFailureText(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

/** The vocabulary itself - an [EmptyBody] with the FAB still present when there is nothing yet
 * (a valid, expected state, not a failure - the identical posture
 * `docs/design/tenant-canned-tags-android.md` §1.5 states for canned's own empty library), or one row per
 * tag in the server's own order. */
@Composable
private fun TagsLoadedBody(
    state: TagsUiState.Loaded,
    onEdit: (Tag) -> Unit,
    onDeleteRequest: (Tag) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        state.error?.let { error -> InlineAlert(text = tagsActionErrorText(error)) }
        Box(modifier = Modifier.weight(1f)) {
            if (state.tags.isEmpty()) {
                EmptyBody(stringResource(R.string.automation_tags_empty))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.tags, key = { it.id }) { tag ->
                        TagRow(
                            tag = tag,
                            busy = state.busy,
                            onEdit = { onEdit(tag) },
                            onDelete = { onDeleteRequest(tag) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** One tag: its name, and the console's own per-row Изменить/Удалить pair
 * (`docs/design/tenant-canned-tags-android.md` §2.4) - both disabled while a mutation is already in
 * flight, the identical guard [TagsViewModel]'s own re-entrancy check enforces server-side of the tap. */
@Composable
private fun TagRow(
    tag: Tag,
    busy: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tag.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onEdit, enabled = !busy) {
            Text(text = stringResource(R.string.automation_tags_edit_action))
        }
        TextButton(onClick = onDelete, enabled = !busy) {
            Text(text = stringResource(R.string.automation_tags_delete_action))
        }
    }
}

/**
 * The create/rename dialog - one name [OutlinedTextField] with a [TagBounds.MAX_NAME_LENGTH] counter,
 * seeded with the tag's own name when [isRename], empty when creating
 * (`docs/design/tenant-canned-tags-android.md` §2.4). Сохранить is disabled while the trimmed name is
 * blank or [busy]; [error] renders inline and keeps the dialog open - it never dismisses itself on a
 * refusal, only [onDismiss] (Отмена, or the system back the dialog's own scrim already handles) does.
 */
@Composable
private fun TagEditorDialog(
    initialName: String,
    isRename: Boolean,
    busy: Boolean,
    error: TagsActionError?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val canSave = name.isNotBlank() && !busy

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(if (isRename) R.string.automation_tags_rename_title else R.string.automation_tags_create_title),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= TagBounds.MAX_NAME_LENGTH) name = it },
                    label = { Text(text = stringResource(R.string.automation_tags_name_label)) },
                    supportingText = { Text(text = "${name.length}/${TagBounds.MAX_NAME_LENGTH}") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { InlineAlert(text = tagsActionErrorText(it)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = canSave) {
                Text(text = stringResource(if (busy) R.string.automation_tags_action_saving else R.string.automation_tags_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

/** An inline banner for a courtesy-validation problem, a server refusal, or a transport failure - the
 * identical tonal-danger-surface shape
 * [ago.chat.android.automation.CannedResponsesScreen]'s own private `InlineAlert` already establishes,
 * restated here for the same "neither file imports composables from the other" reason that file's own doc
 * comment states. */
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

/** The one place [TagsActionError] becomes a sentence - the identical single-call-site discipline
 * [ago.chat.android.automation.cannedResponsesActionErrorText] already establishes for
 * [ago.chat.android.automation.CannedResponsesActionError]. */
@Composable
private fun tagsActionErrorText(error: TagsActionError): String =
    when (error) {
        is TagsActionError.Invalid -> tagValidationProblemText(error.problem)
        is TagsActionError.ServerRefusal -> error.detail
        is TagsActionError.Unavailable -> networkFailureText(error.reason)
    }

/** [TagValidationProblem]'s own two reasons, each its own string - never a shared "invalid name"
 * catch-all, the identical split
 * [ago.chat.android.automation.cannedResponseValidationProblemText] already establishes for the canned
 * screen's own courtesy check. */
@Composable
private fun tagValidationProblemText(problem: TagValidationProblem): String =
    when (problem) {
        TagValidationProblem.NameRequired -> stringResource(R.string.automation_tags_validation_name_required)
        TagValidationProblem.NameTooLong -> stringResource(R.string.automation_tags_validation_name_too_long, TagBounds.MAX_NAME_LENGTH)
    }
