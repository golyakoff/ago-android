package ago.chat.android.devicestorage

import ago.chat.android.R
import ago.chat.android.ui.components.SectionLabel
import ago.chat.android.ui.icons.AgoIcons
import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * `26-256`: Ещё → Администрирование → «Справка» — the app's mirror of `ago-console`'s own
 * `DeviceStorageDisclosurePage` (`/account/device-storage`). A **reference / legal-info** screen a tenant
 * reads to write their own privacy notice: it lists what the AGO chat widget stores in a visitor's
 * **browser storage** (`localStorage`, not cookies), identical for every tenant.
 *
 * **This screen fetches nothing** — exactly as the console page it mirrors. Every row here describes what
 * the widget's *code* does, which is identical for every tenant, so there is no per-site value to load and
 * therefore no view model, no `hiltViewModel()`, and no [ago.chat.android.core.network] call. The whole
 * screen is [DEVICE_STORAGE_DISCLOSURE_ROWS] plus a ready-made snippet; that is why this is a bare
 * `onBack`-only composable, not the Route/ViewModel/Screen triple every *data* screen in this app is.
 *
 * The disclosure rows are a hand-maintained copy of `ago-console`'s own `DEVICE_STORAGE_DISCLOSURE_ROWS`
 * (itself a hand-maintained copy of `ago-widget`'s `WIDGET_STORAGE_DISCLOSURE` — the three repositories
 * build independently, so there is no mechanical link; see that console file's own doc comment for why the
 * gap cannot be closed without a published package neither repository has today).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceStorageRoute(onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.more_administration_reference_row)) },
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
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "intro") { DeviceStorageIntro() }
                item(key = "snippet") { DeviceStoragePrivacySnippet() }
                item(key = "rows-label") {
                    SectionLabel(text = stringResource(R.string.device_storage_rows_label))
                }
                items(
                    count = DEVICE_STORAGE_DISCLOSURE_ROWS.size,
                    key = { index -> DEVICE_STORAGE_DISCLOSURE_ROWS[index].key },
                ) { index ->
                    DeviceStorageRowItem(DEVICE_STORAGE_DISCLOSURE_ROWS[index])
                }
            }
        }
    }
}

/** The intro block: the not-cookies warning, what is stored and under which key namespace, and the
 * erase note — the identical three paragraphs `DeviceStorageDisclosurePage`'s own header panel carries. */
@Composable
private fun DeviceStorageIntro() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.device_storage_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.device_storage_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.device_storage_not_cookies),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.device_storage_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.device_storage_survives_tab_close_note),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.device_storage_erase_note),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * `26-256`: the ready-made privacy snippet — a boilerplate paragraph a tenant can paste into their site's
 * privacy policy, with a copy-to-clipboard action. Legally neutral: it describes browser-storage use and
 * explicitly says it is *not* a cookie, so a tenant does not end up declaring cookies AGO does not set. The
 * text lives in a string resource ([R.string.device_storage_snippet_body]) so the copied value is exactly
 * what the reader sees, in whichever locale the app is running.
 *
 * The [LocalClipboard]/[ClipEntry] shape is the identical one
 * [ago.chat.android.channels.ChannelConnectScreen]'s own reveal field already establishes; the clip label
 * is an OS-level detail (visible only in system clipboard history), never user-facing copy, so it takes no
 * string resource.
 */
@Composable
private fun DeviceStoragePrivacySnippet() {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snippet = stringResource(R.string.device_storage_snippet_body)
    var copied by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.device_storage_snippet_preface),
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        Button(onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ago-privacy-snippet", snippet)))
                copied = true
            }
        }) {
            Text(text = stringResource(R.string.device_storage_snippet_copy))
        }
        if (copied) {
            Text(
                text = stringResource(R.string.device_storage_snippet_copied),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One disclosure row rendered as a card: the technical key (monospace, the suffix a tenant sees after
 * `ago-chat:<site key>:` in dev tools) and the three facts a tenant needs to declare it — what it holds,
 * why it exists, and how long it lives. A per-row card rather than the console's five-column table, which
 * does not fit a phone width. */
@Composable
private fun DeviceStorageRowItem(row: DeviceStorageDisclosureRow) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = row.key,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            DeviceStorageFact(R.string.device_storage_column_holds, row.holdsRes)
            DeviceStorageFact(R.string.device_storage_column_why, row.whyRes)
            DeviceStorageFact(R.string.device_storage_column_lifetime, row.lifetimeRes)
        }
    }
}

@Composable
private fun DeviceStorageFact(
    @StringRes labelRes: Int,
    @StringRes valueRes: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = stringResource(valueRes), style = MaterialTheme.typography.bodyMedium)
    }
}
