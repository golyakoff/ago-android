package ago.chat.android.automation

import ago.chat.android.R
import ago.chat.android.bookings.EmptyBody
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.core.domain.cannedresponses.CannedResponse
import ago.chat.android.core.domain.cannedresponses.CannedResponseBounds
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * `26-220` (`docs/design/tenant-canned-tags-android.md` §1): Автоматизация → «Готовые ответы» - the
 * per-site canned-response library the composer's own `/` picker reads, never matched against a
 * visitor's message the way an auto-reply keyword is (`CannedResponse`'s own doc comment, `ago-chat`).
 * Obtains its own [CannedResponsesViewModel] via [hiltViewModel] - the identical wiring
 * [ago.chat.android.automation.OfflineAutoReplyRoute] already establishes for a drill-in
 * [ago.chat.android.shell.MoreScreen] composes only for an operator holding `site:configure`.
 */
@Composable
internal fun CannedResponsesRoute(
    onBack: () -> Unit,
    viewModel: CannedResponsesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CannedResponsesScreen(
        state = state,
        onRetry = viewModel::refresh,
        onSave = viewModel::addOrReplace,
        onDelete = viewModel::delete,
        onBack = onBack,
    )
}

/**
 * The stateless half - a list, a FAB, and a full-screen editor, rather than `ago-console`'s own "one
 * blank trailing row to type into" idiom, which is dropped here because it fails on a soft keyboard
 * (`scope-inventory.md` §8). This is the app's *first* [FloatingActionButton] - two call sites (here and
 * a future Метки screen) do not yet justify a shared wrapper
 * (`docs/design/tenant-canned-tags-android.md` §5's own premature-generalisation note).
 *
 * **There is no per-item endpoint** ([ago.chat.android.core.domain.cannedresponses.CannedResponsesApi]'s
 * own doc comment): every add, edit or delete is a client-side edit of the in-memory list, then one
 * whole-list `PUT` - this composable never assembles that request itself, it only tells
 * [CannedResponsesViewModel] which index changed.
 *
 * **Which editor is open is transient composition state**, via [remember] - not a
 * [CannedResponsesUiState] arm, the identical discipline
 * [ago.chat.android.schedule.WorkingHoursScreen]'s own edit dialog already follows. A successful save
 * bumps [CannedResponsesUiState.Loaded.savedTick], which is this screen's own signal to close the editor
 * and return to the list; a [CannedResponsesActionError.ServerRefusal]/[CannedResponsesActionError.Unavailable]
 * never bumps it, so the editor stays open on either - "the operator can retry or back out"
 * (`docs/design/tenant-canned-tags-android.md` §1.5).
 */
@Composable
internal fun CannedResponsesScreen(
    state: CannedResponsesUiState,
    onRetry: () -> Unit,
    onSave: (index: Int?, title: String, body: String) -> Unit,
    onDelete: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<CannedResponseEditorTarget?>(null) }

    val savedTick = (state as? CannedResponsesUiState.Loaded)?.savedTick ?: 0
    LaunchedEffect(savedTick) {
        if (savedTick > 0) editing = null
    }

    val target = editing
    if (target != null) {
        val existing =
            (target as? CannedResponseEditorTarget.Existing)?.index?.let { index ->
                (state as? CannedResponsesUiState.Loaded)?.responses?.getOrNull(index)
            }
        CannedResponseEditorScreen(
            initialTitle = existing?.title.orEmpty(),
            initialBody = existing?.body.orEmpty(),
            saving = (state as? CannedResponsesUiState.Loaded)?.saving ?: false,
            error = (state as? CannedResponsesUiState.Loaded)?.error,
            onSave = { title, body -> onSave((target as? CannedResponseEditorTarget.Existing)?.index, title, body) },
            onBack = { editing = null },
        )
    } else {
        CannedResponsesListScreen(
            state = state,
            onRetry = onRetry,
            onAdd = { editing = CannedResponseEditorTarget.New },
            onEdit = { index -> editing = CannedResponseEditorTarget.Existing(index) },
            onDelete = onDelete,
            onBack = onBack,
        )
    }
}

/** Which editor [CannedResponsesScreen] currently shows, if any - a new entry, or an existing row's own
 * index. `private`: this screen's own transient composition state, never read outside it. */
private sealed interface CannedResponseEditorTarget {
    data object New : CannedResponseEditorTarget

    data class Existing(
        val index: Int,
    ) : CannedResponseEditorTarget
}

/** The list screen - Route/Screen split, back arrow, no
 * [ago.chat.android.ui.components.AccountAvatarAction] (a drill-in), the identical shape
 * [ago.chat.android.automation.OfflineAutoReplyScreen]/[ago.chat.android.channels.BrandingScreen]
 * already establish. The «Сохранено» snackbar lives here, not on the editor - it is shown only once the
 * editor has already closed and this screen is what the operator sees. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CannedResponsesListScreen(
    state: CannedResponsesUiState,
    onRetry: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onBack: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf<Int?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.canned_responses_saved)
    val savedTick = (state as? CannedResponsesUiState.Loaded)?.savedTick ?: 0
    LaunchedEffect(savedTick) {
        if (savedTick > 0) snackbarHostState.showSnackbar(savedMessage)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.more_automation_quick_replies_row)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
            floatingActionButton = {
                if (state is CannedResponsesUiState.Loaded) {
                    FloatingActionButton(onClick = onAdd) {
                        Icon(imageVector = AgoIcons.Plus, contentDescription = stringResource(R.string.canned_responses_add_action))
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    CannedResponsesUiState.Loading -> LoadingBody()
                    is CannedResponsesUiState.Failed -> CannedResponsesFailedBody(reason = state.reason, onRetry = onRetry)
                    is CannedResponsesUiState.Loaded ->
                        CannedResponsesLoadedBody(
                            state = state,
                            onEdit = onEdit,
                            onDeleteRequest = { index -> confirmingDelete = index },
                        )
                }
            }
        }
    }

    confirmingDelete?.let { index ->
        val title =
            (state as? CannedResponsesUiState.Loaded)
                ?.responses
                ?.getOrNull(index)
                ?.title
                .orEmpty()
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text(text = stringResource(R.string.canned_responses_delete_title)) },
            text = { Text(text = stringResource(R.string.canned_responses_delete_message, title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = null
                    onDelete(index)
                }) {
                    Text(text = stringResource(R.string.canned_responses_delete_action))
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

/** The read itself failed - the identical "title, [networkFailureText], retry" shape
 * [ago.chat.android.automation.OfflineAutoReplyScreen]'s own private failed body already establishes,
 * restated here since neither file imports composables from the other. */
@Composable
private fun CannedResponsesFailedBody(
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

/** The library itself - an [EmptyBody] with the FAB still present when there is nothing yet
 * (`docs/design/tenant-canned-tags-android.md` §1.5's own "a valid, expected state, not a failure"), or
 * one row per response, oldest-added-first-arranged order preserved exactly as the operator built it. */
@Composable
private fun CannedResponsesLoadedBody(
    state: CannedResponsesUiState.Loaded,
    onEdit: (Int) -> Unit,
    onDeleteRequest: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        state.error?.let { error -> InlineAlert(text = cannedResponsesActionErrorText(error)) }
        Box(modifier = Modifier.weight(1f)) {
            if (state.responses.isEmpty()) {
                EmptyBody(stringResource(R.string.canned_responses_empty))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(state.responses) { index, response ->
                        CannedResponseRow(
                            response = response,
                            onClick = { onEdit(index) },
                            onDelete = { onDeleteRequest(index) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** One saved response: its title (bold, ellipsised) and a one-line body preview, tapping opens the
 * editor for this index; a trailing Удалить opens the delete confirm
 * (`docs/design/tenant-canned-tags-android.md` §1.5). */
@Composable
private fun CannedResponseRow(
    response: CannedResponse,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = response.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = response.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onDelete) {
            Text(text = stringResource(R.string.canned_responses_delete_action))
        }
    }
}

/**
 * The full-screen editor - a title field (single line, [CannedResponseBounds.MAX_TITLE_LENGTH] counter)
 * and a body field (multi-line, [CannedResponseBounds.MAX_BODY_LENGTH] counter), a primary Сохранить
 * disabled while either is blank or [saving] (`docs/design/tenant-canned-tags-android.md` §1.5). Back
 * (system or the app bar's own arrow) returns to the list without saving - a non-empty draft is
 * transient and not persisted, acceptable for a short one-shot input the same way that section states
 * for [ago.chat.android.schedule.WorkingHoursScreen]'s own edit dialog.
 *
 * [error] renders here, not on the list, whenever this screen is the one showing - it is the same
 * [CannedResponsesUiState.Loaded.error] the list screen reads, just displayed wherever the write that
 * produced it was started from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CannedResponseEditorScreen(
    initialTitle: String,
    initialBody: String,
    saving: Boolean,
    error: CannedResponsesActionError?,
    onSave: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    var body by rememberSaveable(initialBody) { mutableStateOf(initialBody) }
    val canSave = title.isNotBlank() && body.isNotBlank() && !saving

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.canned_responses_editor_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
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
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= CannedResponseBounds.MAX_TITLE_LENGTH) title = it },
                    label = { Text(text = stringResource(R.string.canned_responses_title_label)) },
                    supportingText = { Text(text = "${title.length}/${CannedResponseBounds.MAX_TITLE_LENGTH}") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { if (it.length <= CannedResponseBounds.MAX_BODY_LENGTH) body = it },
                    label = { Text(text = stringResource(R.string.canned_responses_body_label)) },
                    supportingText = { Text(text = "${body.length}/${CannedResponseBounds.MAX_BODY_LENGTH}") },
                    minLines = 4,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let { InlineAlert(text = cannedResponsesActionErrorText(it)) }

                Button(
                    onClick = { onSave(title.trim(), body) },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text =
                            stringResource(
                                if (saving) R.string.canned_responses_action_saving else R.string.canned_responses_action_save,
                            ),
                    )
                }
            }
        }
    }
}

/** An inline banner for a courtesy-validation problem, a server refusal, or a transport failure - the
 * identical tonal-danger-surface shape [ago.chat.android.automation.OfflineAutoReplyScreen]'s own
 * private `InlineAlert` already establishes, restated here for the same "neither file imports
 * composables from the other" reason that file's own doc comment states. */
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

/** The one place [CannedResponsesActionError] becomes a sentence - the identical single-call-site
 * discipline [ago.chat.android.automation.OfflineAutoReplyScreen]'s own
 * `offlineAutoReplyActionErrorText` already establishes for
 * [ago.chat.android.automation.OfflineAutoReplyActionError]. */
@Composable
private fun cannedResponsesActionErrorText(error: CannedResponsesActionError): String =
    when (error) {
        is CannedResponsesActionError.Invalid -> cannedResponseValidationProblemText(error.problem)
        is CannedResponsesActionError.ServerRefusal -> error.detail
        is CannedResponsesActionError.Unavailable -> networkFailureText(error.reason)
    }

/** [CannedResponseValidationProblem]'s own five reasons, each its own string
 * (`docs/design/tenant-canned-tags-android.md` §1.5) - never a shared "invalid response" catch-all, the
 * identical split `offlineAutoReplyValidationProblemText` already establishes for the auto-reply
 * screen's own courtesy check. */
@Composable
private fun cannedResponseValidationProblemText(problem: CannedResponseValidationProblem): String =
    when (problem) {
        CannedResponseValidationProblem.TooMany ->
            stringResource(R.string.canned_responses_validation_too_many, CannedResponseBounds.MAX_COUNT)

        CannedResponseValidationProblem.TitleRequired ->
            stringResource(R.string.canned_responses_validation_title_required)

        CannedResponseValidationProblem.TitleTooLong ->
            stringResource(R.string.canned_responses_validation_title_too_long, CannedResponseBounds.MAX_TITLE_LENGTH)

        is CannedResponseValidationProblem.BodyRequired ->
            stringResource(R.string.canned_responses_validation_body_required, problem.title)

        is CannedResponseValidationProblem.BodyTooLong ->
            stringResource(R.string.canned_responses_validation_body_too_long, CannedResponseBounds.MAX_BODY_LENGTH)
    }
