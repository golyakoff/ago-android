package ago.chat.android.automation

import ago.chat.android.R
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.core.domain.autoreply.OfflineAutoReplyBounds
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.ui.components.SectionLabel
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.calvin.reorderable.ReorderableColumn

/**
 * `26-192`/`C5` (`docs/design/tenant-channels-android.md` §4.3/§4.4): Автоматизация → «Автоответ вне
 * смены», obtaining its own [OfflineAutoReplyViewModel] via [hiltViewModel] - the identical wiring
 * [ago.chat.android.channels.BrandingRoute] already establishes for a drill-in
 * [ago.chat.android.shell.MoreScreen] composes only for an operator holding `site:configure`
 * (`MoreScreen`'s own `AUTOMATION_AFTER_HOURS_ROW_ID` branch, moved under that gate by this slice).
 */
@Composable
internal fun OfflineAutoReplyRoute(
    onBack: () -> Unit,
    viewModel: OfflineAutoReplyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    OfflineAutoReplyScreen(
        state = state,
        onEnabledChange = viewModel::setEnabled,
        onFallbackReplyChange = viewModel::setFallbackReply,
        onAddRule = viewModel::addRule,
        onRuleKeywordChange = viewModel::updateRuleKeyword,
        onRuleReplyChange = viewModel::updateRuleReply,
        onRemoveRule = viewModel::removeRule,
        onMoveRule = viewModel::moveRule,
        onSave = viewModel::save,
        onRetry = viewModel::refresh,
        onBack = onBack,
    )
}

/**
 * The stateless screen - Route/Screen split, back arrow, no
 * [ago.chat.android.ui.components.AccountAvatarAction] (a drill-in), the identical shape
 * [ago.chat.android.channels.ChannelConnectScreen]/[ago.chat.android.channels.BrandingScreen] already
 * establish. The app-bar title reuses [R.string.more_automation_after_hours_row] - one resource, two
 * call sites, the same "one word, one string" shape `channels_install_title` already takes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfflineAutoReplyScreen(
    state: OfflineAutoReplyUiState,
    onEnabledChange: (Boolean) -> Unit,
    onFallbackReplyChange: (String) -> Unit,
    onAddRule: () -> Unit,
    onRuleKeywordChange: (Long, String) -> Unit,
    onRuleReplyChange: (Long, String) -> Unit,
    onRemoveRule: (Long) -> Unit,
    onMoveRule: (Int, Int) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.automation_auto_reply_saved)
    val savedTick = (state as? OfflineAutoReplyUiState.Loaded)?.savedTick ?: 0
    LaunchedEffect(savedTick) {
        if (savedTick > 0) snackbarHostState.showSnackbar(savedMessage)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.more_automation_after_hours_row)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    OfflineAutoReplyUiState.Loading -> LoadingBody()
                    is OfflineAutoReplyUiState.Failed -> OfflineAutoReplyFailedBody(reason = state.reason, onRetry = onRetry)
                    is OfflineAutoReplyUiState.Loaded ->
                        OfflineAutoReplyLoadedBody(
                            state = state,
                            onEnabledChange = onEnabledChange,
                            onFallbackReplyChange = onFallbackReplyChange,
                            onAddRule = onAddRule,
                            onRuleKeywordChange = onRuleKeywordChange,
                            onRuleReplyChange = onRuleReplyChange,
                            onRemoveRule = onRemoveRule,
                            onMoveRule = onMoveRule,
                            onSave = onSave,
                        )
                }
            }
        }
    }
}

/** The read itself failed - the identical "title, [networkFailureText], retry" shape
 * [ago.chat.android.channels.BrandingScreen]'s own private failed body already establishes, restated
 * here since neither file imports composables from the other. */
@Composable
private fun OfflineAutoReplyFailedBody(
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

/**
 * Enabled switch, fallback reply, the reorderable rule list, and Save - one scrollable column, since
 * unlike [ago.chat.android.channels.BrandingScreen]'s own two independent writes this screen has exactly
 * one save surface (`OfflineAutoReplyViewModel`'s own doc comment states why: rule order is behaviour,
 * so there is no safe partial update to split this into).
 */
@Composable
private fun OfflineAutoReplyLoadedBody(
    state: OfflineAutoReplyUiState.Loaded,
    onEnabledChange: (Boolean) -> Unit,
    onFallbackReplyChange: (String) -> Unit,
    onAddRule: () -> Unit,
    onRuleKeywordChange: (Long, String) -> Unit,
    onRuleReplyChange: (Long, String) -> Unit,
    onRemoveRule: (Long) -> Unit,
    onMoveRule: (Int, Int) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // `docs/design/tenant-channels-android.md` §4.3: first-match-wins, stated on screen rather than
        // left implicit - the single most surprising property of this feature, and the whole reason the
        // rule list below is reorderable at all.
        Text(
            text = stringResource(R.string.automation_auto_reply_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.automation_auto_reply_enabled_label),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.enabled, onCheckedChange = onEnabledChange, enabled = !state.saving)
        }

        OutlinedTextField(
            value = state.fallbackReply,
            onValueChange = onFallbackReplyChange,
            label = { Text(text = stringResource(R.string.automation_auto_reply_fallback_label)) },
            minLines = 3,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel(text = stringResource(R.string.automation_auto_reply_rules_section))

        // A plain, non-lazy `ReorderableColumn`: `OfflineAutoReplyBounds.MAX_RULES` bounds this list at
        // 20 rows, small enough to lay out eagerly inside the scrollable `Column` above rather than
        // reaching for `rememberReorderableLazyListState`/`LazyColumn`, which this screen has no other
        // reason to need.
        ReorderableColumn(
            list = state.rules,
            onSettle = onMoveRule,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { index, rule, isDragging ->
            key(rule.id) {
                ReorderableItem {
                    AutoReplyRuleCard(
                        index = index,
                        rule = rule,
                        isDragging = isDragging,
                        isFirst = index == 0,
                        isLast = index == state.rules.lastIndex,
                        enabled = !state.saving,
                        dragHandleModifier = Modifier.draggableHandle(),
                        onKeywordChange = { onRuleKeywordChange(rule.id, it) },
                        onReplyChange = { onRuleReplyChange(rule.id, it) },
                        onRemove = { onRemoveRule(rule.id) },
                        onMoveUp = { onMoveRule(index, index - 1) },
                        onMoveDown = { onMoveRule(index, index + 1) },
                    )
                }
            }
        }

        TextButton(onClick = onAddRule, enabled = !state.saving) {
            Text(text = stringResource(R.string.automation_auto_reply_add_rule_action))
        }

        state.saveError?.let { error -> InlineAlert(text = offlineAutoReplyActionErrorText(error)) }

        Button(onClick = onSave, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
            Text(
                text =
                    stringResource(
                        if (state.saving) R.string.automation_auto_reply_action_saving else R.string.automation_auto_reply_action_save,
                    ),
            )
        }
    }
}

/**
 * One rule row: a leading drag handle plus its own visible order number, the keyword/reply fields, and a
 * trailing delete - order is drawn as both position and an explicit number
 * (`docs/design/tenant-channels-android.md` §4.3's own "a small '1, 2, 3…' index"). [onMoveUp]/[onMoveDown]
 * are the keyboard/TalkBack-reachable equivalent of dragging [dragHandleModifier] - real, independently
 * focusable [IconButton]s rather than a custom accessibility action on the handle, since a drag gesture
 * itself has no TalkBack equivalent (that doc section's own "whichever is chosen, keyboard/TalkBack users
 * get a semantic move up/down action").
 *
 * **[AgoIcons.MoreVertical] stands in for a dedicated grip glyph.** This app's hand-drawn icon set is
 * transcribed from an approved mockup sprite (`AgoIcons`'s own doc comment), and no mockup glyph for a
 * drag handle exists yet for this screen; rather than invent an unapproved vector, this reuses the
 * existing dot-pattern icon closest to a grip's own visual affordance. Worth a word at review, not a
 * blocker: swap it for a purpose-drawn handle glyph if/when one is added to the approved set.
 */
@Composable
private fun AutoReplyRuleCard(
    index: Int,
    rule: AutoReplyRuleDraft,
    isDragging: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    enabled: Boolean,
    dragHandleModifier: Modifier,
    onKeywordChange: (String) -> Unit,
    onReplyChange: (String) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val elevation by animateDpAsState(targetValue = if (isDragging) 4.dp else 0.dp, label = "autoReplyRuleElevation")

    Surface(
        shadowElevation = elevation,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AgoIcons.MoreVertical,
                    contentDescription = stringResource(R.string.automation_auto_reply_drag_handle_description),
                    modifier = dragHandleModifier.size(24.dp),
                )
                Text(
                    text = stringResource(R.string.automation_auto_reply_rule_index, index + 1),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                IconButton(onClick = onMoveUp, enabled = enabled && !isFirst) {
                    Icon(
                        imageVector = AgoIcons.ChevronRight,
                        contentDescription = stringResource(R.string.automation_auto_reply_move_up_action),
                        modifier = Modifier.rotate(-90f),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = enabled && !isLast) {
                    Icon(
                        imageVector = AgoIcons.ChevronRight,
                        contentDescription = stringResource(R.string.automation_auto_reply_move_down_action),
                        modifier = Modifier.rotate(90f),
                    )
                }
            }

            OutlinedTextField(
                value = rule.keyword,
                onValueChange = onKeywordChange,
                label = { Text(text = stringResource(R.string.automation_auto_reply_rule_keyword_label)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = rule.reply,
                onValueChange = onReplyChange,
                label = { Text(text = stringResource(R.string.automation_auto_reply_rule_reply_label)) },
                minLines = 2,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRemove, enabled = enabled) {
                    Text(text = stringResource(R.string.automation_auto_reply_remove_rule_action))
                }
            }
        }
    }
}

/** An inline banner for a courtesy-validation problem, a server refusal, or a transport failure - the
 * identical tonal-danger-surface shape [ago.chat.android.channels.BrandingScreen]'s own private
 * `InlineAlert` already establishes, restated here for the same "neither file imports composables from
 * the other" reason that file's own doc comment states. */
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

/** The one place [OfflineAutoReplyActionError] becomes a sentence - the identical single-call-site
 * discipline [ago.chat.android.channels.BrandingScreen]'s own `brandingActionErrorText` already
 * establishes for [ago.chat.android.channels.BrandingActionError]. */
@Composable
private fun offlineAutoReplyActionErrorText(error: OfflineAutoReplyActionError): String =
    when (error) {
        is OfflineAutoReplyActionError.Invalid -> offlineAutoReplyValidationProblemText(error.problem)
        is OfflineAutoReplyActionError.ServerRefusal -> error.detail
        is OfflineAutoReplyActionError.Unavailable -> networkFailureText(error.reason)
    }

/** [OfflineAutoReplyValidationProblem]'s own seven reasons, each its own string
 * (`docs/design/tenant-channels-android.md` §4.3) - never a shared "invalid draft" catch-all, the
 * identical split `logoValidationProblemTextRes` already establishes for the branding screen's own
 * courtesy check. */
@Composable
private fun offlineAutoReplyValidationProblemText(problem: OfflineAutoReplyValidationProblem): String =
    when (problem) {
        OfflineAutoReplyValidationProblem.FallbackRequired ->
            stringResource(R.string.automation_auto_reply_validation_needs_fallback)

        OfflineAutoReplyValidationProblem.FallbackTooLong ->
            stringResource(R.string.automation_auto_reply_validation_fallback_too_long, OfflineAutoReplyBounds.MAX_REPLY_LENGTH)

        OfflineAutoReplyValidationProblem.TooManyRules ->
            stringResource(R.string.automation_auto_reply_validation_too_many_rules, OfflineAutoReplyBounds.MAX_RULES)

        OfflineAutoReplyValidationProblem.RuleKeywordRequired ->
            stringResource(R.string.automation_auto_reply_validation_keyword_required)

        is OfflineAutoReplyValidationProblem.RuleReplyRequired ->
            stringResource(R.string.automation_auto_reply_validation_reply_required, problem.keyword)

        OfflineAutoReplyValidationProblem.RuleKeywordTooLong ->
            stringResource(R.string.automation_auto_reply_validation_keyword_too_long, OfflineAutoReplyBounds.MAX_KEYWORD_LENGTH)

        OfflineAutoReplyValidationProblem.RuleReplyTooLong ->
            stringResource(R.string.automation_auto_reply_validation_reply_too_long, OfflineAutoReplyBounds.MAX_REPLY_LENGTH)
    }
