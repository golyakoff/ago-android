package ago.chat.android.shell

import ago.chat.android.R
import ago.chat.android.core.domain.navigation.BottomDestination
import ago.chat.android.core.domain.navigation.visibleBottomDestinations
import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/**
 * `26-16`: the app's whole shell — the bottom navigation bar and everything the back-button contract
 * needs, built once here rather than left implicit in each screen. Replaces
 * `ago.chat.android.signin.SignInScreens`'s own retired `SignedInHost`, which this module's own doc
 * comment already named as this item's to answer ("adding [Navigation Compose] here... would be a
 * guess at that item's answer").
 *
 * ## Why Navigation Compose's *ordinary* bottom-navigation recipe is the whole of the back contract's
 * clause 3, with no custom `BackHandler` anywhere in this file
 *
 * Each [BottomDestination] click below does exactly what every Android sample app's bottom bar does —
 * `popUpTo(startDestinationId) { saveState = true }`, `launchSingleTop = true`, `restoreState = true`.
 * With [BottomDestination.Conversations] as this `NavHost`'s own `startDestination`, that recipe keeps
 * the back stack at **never more than two entries**: `[conversations]` alone, or `[conversations, X]`
 * for whichever other tab is current — `popUpTo` only ever pops entries *above* the start destination,
 * never the start destination itself, so `conversations` is never removed from the stack at all, only
 * ever not-currently-drawn. Two consequences fall out of that shape for free, and neither is hand-coded
 * anywhere in this file:
 *
 * - **Back off any tab other than Диалоги lands on Диалоги** — `NavHost`'s own default back handling
 *   (registered automatically against `LocalOnBackPressedDispatcherOwner`, the same dispatcher
 *   `BackHandler` composables register against) pops the top entry, which is always `X`, leaving
 *   `conversations`.
 * - **Back on Диалоги exits the app** — the back stack is down to its one, un-poppable entry, so
 *   `NavHost` disables its own callback and the system's default "no enabled callback claimed this"
 *   behaviour runs, which is finishing the `Activity`.
 *
 * That is clause 3 in full. Clauses 1 (thread → list) and 2 (Ещё → its own list) are each solved
 * *inside* their own destination's composable instead — [ConversationsTabHost]'s hand-rolled
 * list/thread switch and [MoreScreen]'s own `openRowId` — precisely because a `BackHandler` registered
 * by a composable further down the tree is consumed before this `NavHost`'s own default handling ever
 * runs, the ordinary Compose back-dispatcher stacking rule, not a mechanism this file adds.
 *
 * ## Why [Conversations] never carries a saved-and-restored state at all
 *
 * Because it is the graph's own `startDestination`, its `NavBackStackEntry` is **never popped**, so its
 * `ViewModelStore` and every `rememberSaveable` inside it survive a tab switch exactly as if nothing had
 * navigated away — a stronger guarantee than `restoreState = true` gives the other four tabs, which are
 * genuinely detached and reattached. This is what keeps `ConversationListViewModel`/`ThreadViewModel`
 * behaving identically to how `26-14`/`26-15` already proved them, with nothing in this item touching
 * either class.
 */
@Composable
public fun AppShellRoute(
    activeSiteId: String?,
    hubConnectionState: OperatorHubConnectionState,
    onSignOut: () -> Unit,
    viewModel: AppShellViewModel = hiltViewModel(),
) {
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()

    // `26-17`: the Settings screen's own site switcher writes a new site *through* `ActiveSiteSelection`
    // (`di/AppModule`'s single source of truth) rather than through this value, so this local override
    // is what keeps this call site's own `activeSiteId` in step with it, without this route needing a
    // view model of its own to hold that value. Keyed on the real `activeSiteId` parameter so a genuine
    // upstream change (a fresh sign-in choosing a different site) still wins over a stale local override.
    var currentActiveSiteId by rememberSaveable(activeSiteId) { mutableStateOf(activeSiteId) }

    AppShellScreen(
        permissions = permissions,
        loadError = loadError,
        activeSiteId = currentActiveSiteId,
        hubConnectionState = hubConnectionState,
        onRetry = viewModel::retry,
        onSignOut = onSignOut,
        onSiteSwitched = { newSiteId -> currentActiveSiteId = newSiteId },
    )
}

/**
 * The stateless half — see [AppShellRoute]'s own doc comment for why [permissions] is read here as a
 * plain value rather than through `hiltViewModel()`: every back-contract UI test drives this function
 * directly, with a fixed [OperatorPermissions.Known] and no Hilt component in play at all, the same
 * "route wires, screen renders, a test substitutes its own state" split every other screen in this app
 * already follows.
 *
 * [conversationsTab] defaults to the real [ConversationsTabHost] — which itself needs Hilt's
 * `hiltViewModel()` for [ago.chat.android.conversations.ConversationListViewModel] — and exists as an
 * overridable slot for exactly one reason: the back-button-contract tests that exercise clauses 2 and
 * 3 (Ещё's own back stack, and the bottom-bar's back-to-Диалоги rule) are about the navigation graph
 * itself, not about Диалоги's own content, and substituting a trivial composable here lets those tests
 * drive the *real* `NavHost`/bottom-bar wiring below with no Hilt component in play at all — the
 * identical reasoning [ConversationListRoute]/[ThreadRoute] already apply to their own `viewModel`
 * parameter, one level up.
 */
@Composable
internal fun AppShellScreen(
    permissions: OperatorPermissions,
    loadError: String?,
    activeSiteId: String?,
    hubConnectionState: OperatorHubConnectionState,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    onSiteSwitched: (String) -> Unit = {},
    conversationsTab: @Composable () -> Unit = {
        ConversationsTabHost(activeSiteId = activeSiteId, hubConnectionState = hubConnectionState, onSignOut = onSignOut)
    },
    // `26-17`: the identical "Hilt-avoidance slot" [conversationsTab] above already is, for the same
    // reason - `BackContractMoreScreenTest` drives the real `NavHost`/`MoreScreen`/back-stack mechanics
    // with no Hilt component in play, and the default below is the one place `SettingsRoute`'s own
    // `hiltViewModel()` call would otherwise force one into existence. The two callbacks this slot is
    // handed - `onBack` (closes the row, back to the Ещё list) and `onSiteSwitched` (see
    // [AppShellContent]'s own doc comment) - are supplied at the call site inside [AppShellContent],
    // not baked into this default, because the second one needs the `NavController` only that function
    // owns.
    settingsScreen: @Composable (onBack: () -> Unit, onSiteSwitched: (String) -> Unit) -> Unit = { onBack, onSwitched ->
        SettingsRoute(onBack = onBack, onSignOut = onSignOut, onSiteSwitched = onSwitched)
    },
) {
    when (permissions) {
        OperatorPermissions.Unknown ->
            if (loadError != null) {
                PermissionsLoadFailedScreen(message = loadError, onRetry = onRetry)
            } else {
                PermissionsLoadingScreen()
            }

        is OperatorPermissions.Known ->
            AppShellContent(
                permissions = permissions,
                conversationsTab = conversationsTab,
                settingsScreen = settingsScreen,
                onSiteSwitched = onSiteSwitched,
            )
    }
}

/**
 * `26-17`'s own addition to this function: the More tab's `settingsScreen` slot is wrapped here, not at
 * [AppShellScreen]'s own default, so that a real site switch also does the one thing only this function
 * can — turn "the switch finished" into "the bottom bar itself is now showing Диалоги", the identical
 * `navController.navigate(...)` recipe this file's own bottom-bar `onClick` already uses, reused rather
 * than re-invented. [onSiteSwitched] (the plain, `NavController`-free half) still bubbles further up, to
 * [AppShellRoute]'s own local override - see that function's doc comment for why the site shown while
 * *not* switching lives there rather than here.
 */
@Composable
private fun AppShellContent(
    permissions: OperatorPermissions.Known,
    conversationsTab: @Composable () -> Unit,
    settingsScreen: @Composable (onBack: () -> Unit, onSiteSwitched: (String) -> Unit) -> Unit,
    onSiteSwitched: (String) -> Unit,
) {
    val navController = rememberNavController()
    val destinations = remember(permissions) { visibleBottomDestinations(permissions) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    val route = destination.route()
                    NavigationBarItem(
                        selected = currentRoute == route,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(text = destination.emoji(), style = MaterialTheme.typography.titleMedium) },
                        label = { Text(text = stringResource(destination.labelRes())) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = BottomDestination.Conversations.route(),
            modifier = Modifier.padding(padding),
        ) {
            composable(BottomDestination.Conversations.route()) { conversationsTab() }
            composable(BottomDestination.Bookings.route()) { BookingsPlaceholderScreen() }
            composable(BottomDestination.Team.route()) { TeamPlaceholderScreen() }
            composable(BottomDestination.Analytics.route()) { AnalyticsPlaceholderScreen() }
            composable(BottomDestination.More.route()) {
                MoreScreen(
                    settingsScreen = { onBack ->
                        settingsScreen(onBack) { newSiteId ->
                            onSiteSwitched(newSiteId)
                            navController.navigate(BottomDestination.Conversations.route()) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        }
    }
}

/** The route string [BottomDestination] is navigated by — kept here, not on the `:core:domain` enum
 * itself, since a Navigation Compose route is a UI/wiring detail the domain module has no reason to
 * know the shape of (`:core:domain`'s own "no Android, no framework" rule). */
internal fun BottomDestination.route(): String =
    when (this) {
        BottomDestination.Conversations -> "conversations"
        BottomDestination.Bookings -> "bookings"
        BottomDestination.Team -> "team"
        BottomDestination.Analytics -> "analytics"
        BottomDestination.More -> "more"
    }

@Composable
internal fun BottomDestination.labelRes(): Int =
    when (this) {
        BottomDestination.Conversations -> R.string.nav_conversations
        BottomDestination.Bookings -> R.string.nav_bookings
        BottomDestination.Team -> R.string.nav_team
        BottomDestination.Analytics -> R.string.nav_analytics
        BottomDestination.More -> R.string.nav_more
    }

/** A plain emoji glyph, matching this app's own established convention of drawing a small, fixed
 * symbol as plain text rather than pulling in an icon font/library for one bar
 * (`ago.chat.android.thread.ThreadScreen`'s own back arrow, `"←"`, is the precedent). */
internal fun BottomDestination.emoji(): String =
    when (this) {
        BottomDestination.Conversations -> "💬"
        BottomDestination.Bookings -> "📅"
        BottomDestination.Team -> "👥"
        BottomDestination.Analytics -> "📊"
        BottomDestination.More -> "☰"
    }

@Composable
private fun PermissionsLoadingScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.app_shell_permissions_loading),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun PermissionsLoadFailedScreen(
    message: String,
    onRetry: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_shell_permissions_load_failed_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
                Text(text = stringResource(R.string.action_retry))
            }
        }
    }
}
