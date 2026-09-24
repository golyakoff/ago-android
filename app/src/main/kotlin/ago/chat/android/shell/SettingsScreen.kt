package ago.chat.android.shell

import ago.chat.android.BuildConfig
import ago.chat.android.R
import ago.chat.android.core.domain.identity.Tenancy
import ago.chat.android.core.domain.identity.TenancyListing
import ago.chat.android.devices.NotificationSettingsRoute
import ago.chat.android.devices.PushAvailability
import ago.chat.android.devices.PushUnavailableReason
import ago.chat.android.ui.components.IdentifierText
import ago.chat.android.ui.components.SectionLabel
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.ThemeMode
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    // `26-19`: the notification-settings screen's own local drill-in - the identical
    // `rememberSaveable` boolean + `BackHandler` shape [MoreScreen]'s own `openRowId` already is for
    // Ещё's one-level-deep rows, rather than a second global `NavHost` route
    // ([ago.chat.android.devices.NotificationSettingsRoute]'s own doc comment states the full reasoning
    // for staying local here instead of joining `SETTINGS_ROUTE` as a sibling).
    var showingNotificationSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showingNotificationSettings) { showingNotificationSettings = false }

    if (showingNotificationSettings) {
        NotificationSettingsRoute(onBack = { showingNotificationSettings = false })
        return
    }

    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val tenancies by viewModel.tenancies.collectAsStateWithLifecycle()
    val currentSiteId by viewModel.currentSiteId.collectAsStateWithLifecycle()
    val switching by viewModel.switching.collectAsStateWithLifecycle()
    val pushAvailability by viewModel.pushAvailability.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.siteSwitched.collect { newSiteId -> onSiteSwitched(newSiteId) }
    }

    // `26-18`: [SettingsViewModel.notificationsEnabled]'s own doc comment states why `ON_RESUME` - the
    // identical `LifecycleEventObserver` shape `ThreadRoute`'s own `ON_STOP` draft-flush already
    // establishes, on the opposite event: an operator who left this screen, flipped the system switch,
    // and came straight back must see the current truth, not the answer this screen happened to read
    // when it first composed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshNotificationPermission()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val context = LocalContext.current
    SettingsScreen(
        themeMode = themeMode,
        onThemeModeSelected = viewModel::setThemeMode,
        tenancies = tenancies,
        currentSiteId = currentSiteId,
        switching = switching,
        onSwitchSite = viewModel::switchSite,
        pushAvailability = pushAvailability,
        notificationsEnabled = notificationsEnabled,
        onOpenNotificationSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        },
        onManageNotificationChannels = { showingNotificationSettings = true },
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
    pushAvailability: PushAvailability? = null,
    notificationsEnabled: Boolean = true,
    onOpenNotificationSettings: () -> Unit = {},
    onManageNotificationChannels: () -> Unit = {},
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

                // `26-19`: the section header and its entry row into the real notification-settings
                // screen are now unconditional - channel state and quiet hours are worth configuring
                // regardless of `pushAvailability`/`notificationsEnabled`, unlike the two warning texts
                // below, which stay exactly as conditional as `26-18` left them (each names a real
                // problem, and only when one exists).
                item { SectionLabel(stringResource(R.string.settings_notifications_section)) }
                item {
                    Text(
                        text = stringResource(R.string.settings_notifications_manage_action),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onManageNotificationChannels)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }

                // `26-18`: "checkPushAvailability() returning Unavailable produces a state the operator
                // can act on, naming which condition failed" / "denying POST_NOTIFICATIONS leaves the
                // app usable and states what it can no longer do". Hidden entirely rather than shown as
                // a reassuring "everything is fine" row - the identical "hidden, not shown-disabled"
                // convention this screen's own `switchableSites.size > 1` guard above already follows:
                // a row with nothing wrong to report is not a row an operator needs to read.
                val pushUnavailable = pushAvailability as? PushAvailability.Unavailable
                if (pushUnavailable != null || !notificationsEnabled) {
                    if (pushUnavailable != null) {
                        item {
                            Text(
                                text = pushUnavailableReasonText(pushUnavailable.reason),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (!notificationsEnabled) {
                        item {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_notifications_disabled_text),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                OutlinedButton(onClick = onOpenNotificationSettings, modifier = Modifier.padding(top = 8.dp)) {
                                    Text(text = stringResource(R.string.settings_notifications_open_settings_action))
                                }
                            }
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

/** `26-18`: `PushUnavailableReason`'s own four values, each named rather than a single generic
 * "push is unavailable" sentence - `docs/backlog/26-18-*.md`'s own Done-when asks explicitly for
 * "naming which condition failed". */
@Composable
private fun pushUnavailableReasonText(reason: PushUnavailableReason): String =
    when (reason) {
        PushUnavailableReason.HostAppNotInstalled -> stringResource(R.string.push_unavailable_host_app_not_installed)
        PushUnavailableReason.HostAppBackgroundWorkNotGranted -> stringResource(R.string.push_unavailable_background_work_not_granted)
        PushUnavailableReason.Unauthorized -> stringResource(R.string.push_unavailable_unauthorized)
        PushUnavailableReason.Unknown -> stringResource(R.string.push_unavailable_unknown)
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
