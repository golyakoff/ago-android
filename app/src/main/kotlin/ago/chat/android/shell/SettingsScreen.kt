package ago.chat.android.shell

import ago.chat.android.BuildConfig
import ago.chat.android.R
import ago.chat.android.core.domain.identity.Tenancy
import ago.chat.android.core.domain.identity.TenancyListing
import ago.chat.android.ui.components.IdentifierText
import ago.chat.android.ui.components.SectionLabel
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.ThemeMode
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
                item { SectionLabel(stringResource(R.string.settings_theme_section)) }
                item {
                    // `26-77` follow-up, 2026-09-23: a segmented control, matching the mockup's own
                    // `.seg` - three fixed, mutually exclusive options read better as tabs than as a
                    // list of radio rows, and `SegmentedButton` inside `SingleChoiceSegmentedButtonRow`
                    // already sets `Role.RadioButton` on each segment (Material 3's own single-choice
                    // semantics), so `26-66`'s own "announce role, selection, position" Done-when
                    // carries over unchanged rather than being re-earned. `icon = {}` on every segment,
                    // matching `26-78`'s own fix elsewhere in this app - Material 3's default selected
                    // checkmark is exactly what that item removed from every other segmented row.
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = mode == themeMode,
                                onClick = { onThemeModeSelected(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                                icon = {},
                                label = { Text(text = themeModeLabel(mode)) },
                            )
                        }
                    }
                }

                if (switchableSites.size > 1) {
                    item { SectionLabel(stringResource(R.string.settings_site_section)) }
                    item {
                        // Same reasoning as the theme group above: its own `selectableGroup()`, kept
                        // separate from the theme group's, so each announces its own "N of M".
                        Column(modifier = Modifier.selectableGroup()) {
                            switchableSites.forEach { tenancy ->
                                SiteRow(
                                    tenancy = tenancy,
                                    selected = tenancy.siteId == currentSiteId,
                                    enabled = !switching,
                                    onClick = { onSwitchSite(tenancy.siteId) },
                                )
                            }
                        }
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

                item { SectionLabel(stringResource(R.string.settings_about_section)) }
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        AboutLine(stringResource(R.string.settings_about_build_label), BuildConfig.BUILD_TYPE)
                        AboutLine(stringResource(R.string.settings_about_version_label), BuildConfig.VERSION_NAME)
                    }
                }
            }
        }
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
                // `26-66`: `role = Role.RadioButton` - Тема now says the same thing through a
                // `SegmentedButton`'s own built-in role instead, but Сайт's own row count is
                // unbounded, so it keeps this list shape. `enabled` was already threaded through; a
                // `selectable` with a role announces its own disabled state once the role makes it a
                // real control rather than a bare selected node.
                .selectable(selected = selected, enabled = enabled, onClick = onClick, role = Role.RadioButton)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `onClick = null`: the enclosing `Row`'s own `.selectable` above already owns the click - a
        // second, independently-clickable child here is the one thing that would stop this row's own
        // semantics from merging into one node, the recommended Material 3 shape for a row-of-radio-
        // buttons list.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = tenancy.siteName, style = MaterialTheme.typography.bodyLarge)
            // `26-66`: `clearAndSetSemantics {}` drops this node out of the merge entirely rather than
            // giving the row an explicit `contentDescription` that repeats `tenancy.siteName` by hand -
            // the id is eight monospace hex characters meant to be read with the eyes or dictated by a
            // human (this composable's own doc comment), never announced, and excluding it here means
            // the spoken name can never drift from the one already drawn on screen above it.
            IdentifierText(
                id = tenancy.siteId,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clearAndSetSemantics {},
            )
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
