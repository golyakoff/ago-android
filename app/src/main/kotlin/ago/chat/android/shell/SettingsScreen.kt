package ago.chat.android.shell

import ago.chat.android.BuildConfig
import ago.chat.android.R
import ago.chat.android.core.domain.identity.Tenancy
import ago.chat.android.core.domain.identity.TenancyListing
import ago.chat.android.ui.components.IdentifierText
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.ThemeMode
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * `26-17`: wires [SettingsViewModel] — the "route wires, screen renders" split every other screen in
 * this app already follows ([ago.chat.android.conversations.ConversationListRoute]'s own doc comment
 * states it first).
 *
 * [onSiteSwitched] fires once [SettingsViewModel.siteSwitched] does — after both the REST header and the
 * hub connection have actually moved (that class's own doc comment) — carrying the *new* site id up to
 * [AppShellScreen], whose own `AppShellContent` is what turns this into "return to Диалоги": nothing in
 * this file navigates anywhere itself, since a stateless composable three levels away from the
 * `NavController` is the wrong place to hold that responsibility.
 */
@Composable
public fun SettingsRoute(
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onSiteSwitched: (String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val tenancies by viewModel.tenancies.collectAsStateWithLifecycle()
    val currentSiteId by viewModel.currentSiteId.collectAsStateWithLifecycle()
    val switching by viewModel.switching.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.siteSwitched.collect { newSiteId -> onSiteSwitched(newSiteId) }
    }

    SettingsScreen(
        themeMode = themeMode,
        onThemeModeSelected = viewModel::setThemeMode,
        tenancies = tenancies,
        currentSiteId = currentSiteId,
        switching = switching,
        onSwitchSite = viewModel::switchSite,
        onSignOut = onSignOut,
        onBack = onBack,
    )
}

/**
 * The stateless half — every Compose preview and every future UI test targets this function directly.
 *
 * **Текущий сайт is drawn only for an identity holding more than one tenancy.** `docs/backlog/26-17-*.md`
 * itself only ever describes the switcher in terms of "an operator [who] holds operator seats at several
 * sites" (`adr/0068`); an identity with exactly one has nothing to switch *to*, and this app's own
 * established convention for that shape — [buildMoreSections] never drawing a section with no rows,
 * `26-15`'s single-tenancy visitor chip choosing not to render a control with nothing behind it — is
 * "hidden entirely", not "shown, disabled" or "shown, read-only": a disabled control still asks an
 * operator to wonder why, where an absent one asks nothing. The same rule hides it while [tenancies] has
 * not answered yet or answered with a failure ([TenancyListing.Unanswered]) — this screen has no way to
 * know whether that identity holds one seat or several, and showing a switcher that might be lying about
 * having only one option is worse than showing none until the real answer arrives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    tenancies: TenancyListing,
    currentSiteId: String?,
    switching: Boolean,
    onSwitchSite: (String) -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    val switchableSites = (tenancies as? TenancyListing.Known)?.tenancies.orEmpty()

    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.more_settings_row)) },
                    navigationIcon = {
                        // `26-43`: the mockup's `i-back`, a real vector - this used to be a literal
                        // `Text("←")`, the same gap `26-23` had already closed on `ThreadScreen` and
                        // `AppShellScreen`. Matches `ThreadScreen.kt`'s own navigation icon exactly.
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
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
                item { SectionHeader(stringResource(R.string.settings_theme_section)) }
                items(ThemeMode.entries.toList()) { mode ->
                    ThemeModeRow(mode = mode, selected = mode == themeMode, onSelected = { onThemeModeSelected(mode) })
                }

                if (switchableSites.size > 1) {
                    item { SectionHeader(stringResource(R.string.settings_site_section)) }
                    items(switchableSites, key = { it.siteId }) { tenancy ->
                        SiteRow(
                            tenancy = tenancy,
                            selected = tenancy.siteId == currentSiteId,
                            enabled = !switching,
                            onClick = { onSwitchSite(tenancy.siteId) },
                        )
                    }
                    if (switching) {
                        item {
                            Text(
                                text = stringResource(R.string.settings_site_switching),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                item { SectionHeader(stringResource(R.string.settings_about_section)) }
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        AboutLine(stringResource(R.string.settings_about_build_label), BuildConfig.BUILD_TYPE)
                        AboutLine(stringResource(R.string.settings_about_version_label), BuildConfig.VERSION_NAME)
                    }
                    HorizontalDivider()
                }

                item {
                    TextButton(
                        onClick = onSignOut,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text(text = stringResource(R.string.action_sign_out))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ThemeModeRow(
    mode: ThemeMode,
    selected: Boolean,
    onSelected: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelected)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `onClick = null`: the enclosing `Row`'s own `.selectable` above already owns the click - a
        // second, independently-clickable child here is the one thing that would stop its semantics
        // (and therefore its label's text, `onNodeWithText(...).performClick()`'s own target) from
        // merging into the row's, the recommended Material3 shape for exactly this row-of-radio-buttons
        // layout.
        RadioButton(selected = selected, onClick = null)
        Text(text = themeModeLabel(mode), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String =
    when (mode) {
        ThemeMode.System -> stringResource(R.string.settings_theme_system)
        ThemeMode.Light -> stringResource(R.string.settings_theme_light)
        ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
    }

@Composable
private fun SiteRow(
    tenancy: Tenancy,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `onClick = null` - see `ThemeModeRow`'s own comment on why the enclosing `Row` owns the click.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = tenancy.siteName, style = MaterialTheme.typography.bodyLarge)
            IdentifierText(id = tenancy.siteId, style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider()
}

@Composable
private fun AboutLine(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}
