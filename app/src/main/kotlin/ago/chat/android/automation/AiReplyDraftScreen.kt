package ago.chat.android.automation

import ago.chat.android.R
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.core.domain.ai.AiReplyDraftStatus
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * `26-246` (`ago-console`'s own `AiReplyDraftPage`): Автоматизация → «ИИ-подсказки», obtaining its own
 * [AiReplyDraftViewModel] via [hiltViewModel] — the identical wiring [OfflineAutoReplyRoute] establishes
 * for a drill-in [ago.chat.android.shell.MoreScreen] composes only for an operator holding
 * `site:configure` (`MoreScreen`'s own `AUTOMATION_AI_SUGGESTIONS_ROW_ID` branch, under that gate).
 */
@Composable
internal fun AiReplyDraftRoute(
    onBack: () -> Unit,
    viewModel: AiReplyDraftViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AiReplyDraftScreen(
        state = state,
        onSetEnabled = viewModel::setEnabled,
        onRetry = viewModel::refresh,
        onBack = onBack,
    )
}

/**
 * The stateless screen — Route/Screen split, a back arrow, no
 * [ago.chat.android.ui.components.AccountAvatarAction] (a drill-in), the identical shape
 * [OfflineAutoReplyScreen]/[ago.chat.android.faq.ModulesFaqScreen] establish. The app-bar title reuses
 * [R.string.more_automation_ai_suggestions_row] — one resource, two call sites, the same "one word, one
 * string" shape `more_automation_after_hours_row` already takes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiReplyDraftScreen(
    state: AiReplyDraftUiState,
    onSetEnabled: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.automation_ai_draft_saved)
    val savedTick = (state as? AiReplyDraftUiState.Loaded)?.savedTick ?: 0
    LaunchedEffect(savedTick) {
        if (savedTick > 0) snackbarHostState.showSnackbar(savedMessage)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.more_automation_ai_suggestions_row)) },
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
                    AiReplyDraftUiState.Loading -> LoadingBody()
                    is AiReplyDraftUiState.Failed -> AiReplyDraftFailedBody(reason = state.reason, onRetry = onRetry)
                    is AiReplyDraftUiState.Loaded -> AiReplyDraftLoadedBody(state = state, onSetEnabled = onSetEnabled)
                }
            }
        }
    }
}

/** The read itself failed — the identical "message, retry" shape [OfflineAutoReplyScreen]'s own private
 * failed body establishes, restated here since neither file imports composables from the other. */
@Composable
private fun AiReplyDraftFailedBody(
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
 * The three console faces, driven off [AiReplyDraftStatus] rather than a local guess: the add-on is not
 * held ([AiReplyDraftStatus.purchased] `false`), it is held but the two legal statements are not both on
 * record ([AiReplyDraftStatus.setupComplete] `false`), or it is fully set up and the switch is live. Both
 * incomplete faces are read-only notes here — buying the add-on and accepting its terms is the console's
 * own `AiAddOnPage` (`25-04`), not built in this app, so this door points there in prose rather than a
 * dead link.
 */
@Composable
private fun AiReplyDraftLoadedBody(
    state: AiReplyDraftUiState.Loaded,
    onSetEnabled: (Boolean) -> Unit,
) {
    val status = state.status
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.automation_ai_draft_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        InfoCard(
            heading = stringResource(R.string.automation_ai_draft_what_leaves_heading),
            body = stringResource(R.string.automation_ai_draft_what_leaves),
        )

        when {
            !status.purchased ->
                Text(
                    text = stringResource(R.string.automation_ai_draft_not_purchased),
                    style = MaterialTheme.typography.bodyMedium,
                )

            !status.setupComplete ->
                Text(
                    text = stringResource(R.string.automation_ai_draft_setup_incomplete),
                    style = MaterialTheme.typography.bodyMedium,
                )

            else -> AiReplyDraftSwitch(status = status, busy = state.busy, onSetEnabled = onSetEnabled)
        }

        state.actionError?.let { error -> InlineAlert(text = aiReplyDraftActionErrorText(error)) }
    }
}

/** The live switch — the current position stated in words (never an optimistic control), the
 * shared-switch note, and one enable/disable button that re-reads the server after it lands. */
@Composable
private fun AiReplyDraftSwitch(
    status: AiReplyDraftStatus,
    busy: Boolean,
    onSetEnabled: (Boolean) -> Unit,
) {
    Text(
        text =
            stringResource(
                if (status.enabled) R.string.automation_ai_draft_status_on else R.string.automation_ai_draft_status_off,
            ),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        color = if (status.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (status.enabled) {
        status.effectiveFrom?.let { effectiveFrom ->
            Text(
                text = stringResource(R.string.automation_ai_draft_effective_from, formatEffectiveFrom(effectiveFrom)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Text(
        text = stringResource(R.string.automation_ai_draft_shared_switch_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (status.enabled) {
        OutlinedButton(onClick = { onSetEnabled(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.automation_ai_draft_disable_action))
        }
    } else {
        Button(onClick = { onSetEnabled(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.automation_ai_draft_enable_action))
        }
    }
}

/** «Что покидает эту инсталляцию» — a tonal info panel, the identical secondary-container surface this
 * app's info notes already use, kept private here since no shared Alert composable exists yet. */
@Composable
private fun InfoCard(
    heading: String,
    body: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = heading, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Text(text = body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** An inline banner for a server refusal or a transport failure — the identical tonal-danger-surface shape
 * [OfflineAutoReplyScreen]'s own private `InlineAlert` establishes, restated here since neither file
 * imports composables from the other. */
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

/** The one place [AiReplyDraftActionError] becomes a sentence — the identical single-call-site discipline
 * [OfflineAutoReplyScreen]'s own `offlineAutoReplyActionErrorText` establishes. */
@Composable
private fun aiReplyDraftActionErrorText(error: AiReplyDraftActionError): String =
    when (error) {
        is AiReplyDraftActionError.ServerRefusal -> error.detail
        is AiReplyDraftActionError.Unavailable -> networkFailureText(error.reason)
    }

/** Renders the ISO-8601 `effectiveFrom` in the device's own zone — never a raw wire string on screen, the
 * identical "format, never dump the ISO" choice [ago.chat.android.faq.ModulesFaqScreen] makes for its own
 * expiry date. A value that will not parse (an unexpected wire shape that still decoded) falls back to the
 * raw string rather than crashing. */
@Composable
private fun formatEffectiveFrom(effectiveFrom: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(effectiveFrom, locale) {
        runCatching {
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(Instant.parse(effectiveFrom).atZone(ZoneId.systemDefault()))
        }.getOrDefault(effectiveFrom)
    }
}
