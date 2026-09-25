package ago.chat.android.channels

import ago.chat.android.R
import ago.chat.android.bookings.EmptyBody
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.bookings.RefusalBody
import ago.chat.android.ui.components.SectionLabel
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * `26-159`: Каналы → «Установка виджета» — the chat-widget embed snippet and its allowed origins, the
 * app's mirror of `ago-console`'s own `InstallSnippetPage` (Каналы → Установка виджета, `/channels/install`).
 * Obtains its own [InstallWidgetViewModel] via [hiltViewModel], the identical wiring
 * [ago.chat.android.analytics.ConversionReportRoute] already establishes for an Ещё drill-in;
 * [ago.chat.android.shell.MoreScreen] composes this only while the row is open and only for an operator
 * holding `site:configure`, so the view model — and its first network call — come into existence only
 * when one actually opens the screen.
 */
@Composable
internal fun InstallWidgetRoute(
    onBack: () -> Unit,
    viewModel: InstallWidgetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InstallWidgetScreen(state = state, onRetry = viewModel::refresh, onBack = onBack)
}

/**
 * The stateless half — the identical Route/Screen split [ago.chat.android.analytics.ConversionReportScreen]
 * follows: no [ago.chat.android.ui.components.AccountAvatarAction] (a drill-in, not a top-level
 * destination), a back arrow in its place, and the four-arm `when` the app's read screens share.
 *
 * **The allowed origins are read-only.** `Ago.Chat.Api` exposes no `site:configure`-gated write for a chat
 * site's origins (only the platform-owner `PUT /api/v1/owner/sites/{siteId}/allowed-origins`), so — exactly
 * as `InstallSnippetPage` does — this screen shows them with a hint on how to change them rather than an
 * editor that would call a route that does not exist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InstallWidgetScreen(
    state: InstallWidgetUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.channels_install_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = AgoIcons.Back,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    InstallWidgetUiState.Loading -> LoadingBody()
                    is InstallWidgetUiState.Failed ->
                        RefusalBody(
                            reason = state.reason,
                            onRetry = onRetry,
                            unexpectedMessageRes = R.string.channels_install_load_failed_unexpected,
                            transportMessageRes = R.string.channels_install_load_failed_transport,
                        )

                    is InstallWidgetUiState.Loaded -> InstallWidgetContent(state = state)
                }
            }
        }
    }
}

/**
 * The loaded screen: the embed section (a read-only, selectable snippet field the operator copies from),
 * then the allowed-origins section — a read-only list with a change hint. The origins list sits in a
 * weighted [Box] so its empty state can reuse [EmptyBody] (which fills its box), the identical placement
 * [ago.chat.android.bookings.BookingsScreen]'s own empty states use.
 */
@Composable
private fun InstallWidgetContent(state: InstallWidgetUiState.Loaded) {
    Column(modifier = Modifier.fillMaxSize()) {
        SectionLabel(text = stringResource(R.string.channels_install_snippet_label))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.channels_install_embed_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Read-only, selectable so the operator can copy it - the same disabled/read-only
            // `OutlinedTextField` shape `CalendarSetupBody` uses for its own embed snippet, rather than a
            // bare `Text` that reads as body copy.
            OutlinedTextField(
                value = state.embedSnippet,
                onValueChange = {},
                readOnly = true,
                label = { Text(text = stringResource(R.string.channels_install_snippet_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionLabel(text = stringResource(R.string.channels_install_origins_label))
        Text(
            text = stringResource(R.string.channels_install_origins_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.allowedOrigins.isEmpty()) {
                EmptyBody(stringResource(R.string.channels_install_origins_empty))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.allowedOrigins, key = { it }) { origin ->
                        Text(
                            text = origin,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
