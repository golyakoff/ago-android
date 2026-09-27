package ago.chat.android.faq

import ago.chat.android.R
import ago.chat.android.bookings.EmptyBody
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.core.domain.modules.EnabledModule
import ago.chat.android.core.domain.net.NetworkFailure
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-199`/`M1` (`docs/design/tenant-modules-restrictions-android.md` §2.1): Ещё → Автоматизация →
 * «База знаний» — the site's enabled modules, read-only (key, trigger words, entry point, owner/expiry),
 * or the "ask us" empty note when nothing is enabled yet. Obtains its own [ModulesFaqViewModel] via
 * [hiltViewModel] — the identical wiring [ago.chat.android.automation.TagsRoute] already establishes for
 * an Автоматизация drill-in [ago.chat.android.shell.MoreScreen] composes only for an operator holding
 * `site:configure`, so this view model — and its first [ago.chat.android.core.domain.modules.ModulesApi.fetch]
 * call — come into existence only when the row is actually opened.
 *
 * **`ModulesFaq…`, not `Modules…`.** The file names follow the design doc's own naming for the *eventual*
 * combined screen (§2.3, §3.1) even though this item builds only the Модули half — `M2` (a separate,
 * dependent item) adds the «База знаний» knowledge-base panel to this same screen and file rather than
 * opening a second drill-in, since both are one screen on the console this app mirrors.
 */
@Composable
internal fun ModulesFaqRoute(
    onBack: () -> Unit,
    viewModel: ModulesFaqViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModulesFaqScreen(state = state, onRetry = viewModel::refresh, onBack = onBack)
}

/**
 * The stateless half — the identical Route/Screen split
 * [ago.chat.android.restrictions.RestrictedVisitorsScreen] follows: no
 * [ago.chat.android.ui.components.AccountAvatarAction] (a drill-in, not a top-level destination), a back
 * arrow in its place, and a manual «Обновить» action rather than an auto-refresh poll — a status read an
 * operator opens to check, not a live queue, the identical posture that screen's own doc comment states
 * for its own top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModulesFaqScreen(
    state: ModulesFaqUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.more_automation_faq_row)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        TextButton(onClick = onRetry) {
                            Text(text = stringResource(R.string.automation_modules_refresh_action))
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    ModulesFaqUiState.Loading -> LoadingBody()
                    is ModulesFaqUiState.Failed -> ModulesFailedBody(reason = state.reason, onRetry = onRetry)
                    is ModulesFaqUiState.Loaded -> ModulesLoadedBody(modules = state.modules)
                }
            }
        }
    }
}

/** The read itself failed - the identical "message, retry" shape
 * [ago.chat.android.automation.TagsScreen]'s own private `TagsFailedBody` already establishes, restated
 * here since neither file imports composables from the other. */
@Composable
private fun ModulesFailedBody(
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

/** The module list itself - [EmptyBody]'s own "ask us" note (`Ago.Chat.Api`'s own `ModuleEndpoints`: a
 * tenant never turns a module on for itself, `adr/0151`) when nothing is enabled yet, or one card per
 * enabled module in the server's own order. */
@Composable
private fun ModulesLoadedBody(modules: List<EnabledModule>) {
    if (modules.isEmpty()) {
        EmptyBody(stringResource(R.string.automation_modules_empty))
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(modules, key = { it.moduleKey }) { module ->
                ModuleCard(module)
                HorizontalDivider()
            }
        }
    }
}

/** One enabled module: its key, trigger words (when any), entry point, a «Включено владельцем» badge
 * when [EnabledModule.grantedByOwner], and «До {date}» when [EnabledModule.expiresAt] is set - the exact
 * five facts `docs/design/tenant-modules-restrictions-android.md` §2.1 names, read-only. */
@Composable
private fun ModuleCard(module: EnabledModule) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        ModuleCardHeader(module)
        if (module.triggerWords.isNotEmpty()) {
            Text(
                text = stringResource(R.string.automation_modules_trigger_words, module.triggerWords.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            text = stringResource(R.string.automation_modules_entry_point, module.entryPoint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        module.expiresAt?.let { expiresAt ->
            val locale = LocalConfiguration.current.locales[0]
            val formatted = DateTimeFormatter.ofPattern("d MMMM yyyy", locale).format(expiresAt.atZone(ZoneId.systemDefault()))
            Text(
                text = stringResource(R.string.automation_modules_expires_at, formatted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The card's own header row - the module key and, only when [EnabledModule.grantedByOwner], the owner
 * badge beside it. A private top-level function rather than nesting inside [ModuleCard] - the identical
 * flat-file shape [ago.chat.android.restrictions.RestrictedVisitorsScreen]'s own `RestrictionRow` already
 * takes for its own header row. */
@Composable
private fun ModuleCardHeader(module: EnabledModule) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = module.moduleKey, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        if (module.grantedByOwner) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(5.dp),
            ) {
                Text(
                    text = stringResource(R.string.automation_modules_granted_by_owner_badge),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
    }
}
