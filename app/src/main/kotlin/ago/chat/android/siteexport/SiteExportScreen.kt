package ago.chat.android.siteexport

import ago.chat.android.R
import ago.chat.android.bookings.EmptyBody
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.siteexport.SiteExportHistoryItem
import ago.chat.android.core.domain.siteexport.SiteExportStatus
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `26-251` (`ago-console`'s own `SiteExportPage`, ported): Ещё → Администрирование → «Скачать данные» — the
 * export-history list (status, requested/ready timestamps in the device zone) and one "request export"
 * action. A completed export is a link the server hands back ([SiteExportHistoryItem.downloadUrl]); this
 * screen opens it through the platform browser via [LocalUriHandler] rather than inventing any download
 * mechanism of its own — the backend offers a URL, so the app presents a URL, exactly the console's own
 * `<a href={row.downloadUrl}>` shape. Obtains its own [SiteExportViewModel] via [hiltViewModel], the
 * identical wiring [ago.chat.android.products.ProductsRoute] establishes for its own Ещё drill-in.
 *
 * `MoreScreen` composes this row only for an operator holding `site:export` (hide-not-disable), the same
 * permission `ago-console`'s own `SiteExportPage` gates `/account/export` on — so the gate lives one level
 * up in the row list, not as an in-screen check, exactly as every other real Ещё row gates itself. Note
 * this is `site:export`, **not** the `site:configure` every other Администрирование row gates on: the
 * console gates this one page on its own dedicated `SITE_EXPORT_PERMISSION`.
 */
@Composable
internal fun SiteExportRoute(
    onBack: () -> Unit,
    viewModel: SiteExportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SiteExportScreen(
        state = state,
        onRetry = viewModel::refresh,
        onRequestExport = viewModel::requestExport,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SiteExportScreen(
    state: SiteExportUiState,
    onRetry: () -> Unit,
    onRequestExport: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.site_export_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        TextButton(onClick = onRetry) {
                            Text(text = stringResource(R.string.site_export_refresh_action))
                        }
                    },
                )
            },
            bottomBar = {
                if (state is SiteExportUiState.Loaded) {
                    RequestExportBar(state = state, onRequestExport = onRequestExport)
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    SiteExportUiState.Loading -> LoadingBody()
                    is SiteExportUiState.Failed -> SiteExportFailedBody(reason = state.reason, onRetry = onRetry)
                    is SiteExportUiState.Loaded ->
                        if (state.items.isEmpty()) {
                            EmptyBody(text = stringResource(R.string.site_export_empty))
                        } else {
                            SiteExportHistoryList(items = state.items)
                        }
                }
            }
        }
    }
}

/** The always-visible request-export action and its own inline error/refusal — the console's own bottom
 * `Button` plus the `requestError` [ago.chat.android.components.Alert] above it, drawn here as a bottom bar
 * so a long history never scrolls the one action off screen. */
@Composable
private fun RequestExportBar(
    state: SiteExportUiState.Loaded,
    onRequestExport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        state.requestRefusal?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        state.requestError?.let { failure ->
            Text(
                text = networkFailureText(failure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        Button(
            onClick = onRequestExport,
            enabled = !state.requesting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.requesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(text = stringResource(R.string.site_export_request_action))
            }
        }
    }
}

/** One row per past request, newest first — the console's own three columns (requested-at · status/link ·
 * expires-at) folded into a single stacked row, since a phone-width table would not read. */
@Composable
private fun SiteExportHistoryList(items: List<SiteExportHistoryItem>) {
    val uriHandler = LocalUriHandler.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.exportId }) { item ->
            SiteExportRow(item = item, onOpenDownload = { url -> uriHandler.openUri(url) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun SiteExportRow(
    item: SiteExportHistoryItem,
    onOpenDownload: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.site_export_requested_at, formatTimestamp(item.requestedAt)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = statusLabel(item.status),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // A ready export is a link the server handed back; open it through the platform browser (this
        // file's own doc comment). A ready row with no url — the archive was pruned between the read and
        // now — falls through to nothing rather than a dead link. `downloadUrl` is copied into a local
        // `val` first because [SiteExportHistoryItem] lives in `:core:domain`: a property from another
        // module is not stable for a smart-cast, so the null-check has to be on a same-module local.
        val downloadUrl = item.downloadUrl
        if (item.status == SiteExportStatus.Ready && downloadUrl != null) {
            Text(
                text = stringResource(R.string.site_export_download_link),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .clickable { onOpenDownload(downloadUrl) },
            )
        }

        item.expiresAt?.let { expiresAt ->
            Text(
                text = stringResource(R.string.site_export_expires_at, formatTimestamp(expiresAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item.failureReason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The read itself failed — the identical "message, retry" shape [ago.chat.android.products.ProductsScreen]'s
 * own failed body establishes, restated here since neither file imports composables from the other. */
@Composable
private fun SiteExportFailedBody(
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

/** `ago-console`'s own `statusLabel` switch, ported — a closed `when` over the [SiteExportStatus] union
 * (no `else`, so a sixth backend status would fail to compile here rather than fall through silently, the
 * same bar the console's own exhaustive `switch` keeps). */
@Composable
private fun statusLabel(status: SiteExportStatus): String =
    when (status) {
        SiteExportStatus.Pending -> stringResource(R.string.site_export_status_pending)
        SiteExportStatus.Processing -> stringResource(R.string.site_export_status_processing)
        SiteExportStatus.Ready -> stringResource(R.string.site_export_status_ready)
        SiteExportStatus.Failed -> stringResource(R.string.site_export_status_failed)
        SiteExportStatus.Expired -> stringResource(R.string.site_export_status_expired)
    }

private val timestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())

/** Renders an [Instant] in the device's own zone — CLAUDE.md rule 11: transport is UTC, the phone renders
 * in its own IANA zone, the identical `withZone(ZoneId.systemDefault())` shape `ChannelConnectScreen`'s own
 * `CHECKED_AT_FORMAT` already uses. */
private fun formatTimestamp(instant: Instant): String = timestampFormatter.format(instant)
