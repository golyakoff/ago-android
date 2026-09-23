package ago.chat.android.shell

import ago.chat.android.R
import ago.chat.android.analytics.AnalyticsRoute
import ago.chat.android.bookings.BookingsRoute
import ago.chat.android.core.domain.navigation.BottomDestination
import ago.chat.android.core.domain.navigation.visibleBottomDestinations
import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.Permission
import ago.chat.android.core.domain.permissions.holds
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.team.TeamChatRoute
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    // `26-48`: the identical "Hilt-avoidance slot" [conversationsTab] above already is, for
    // [BookingsRoute]'s own `hiltViewModel()` call - `BackContractBottomBarTest`'s own
    // `clause3_backNeverWalksThroughPreviouslyVisitedTabs` is the one back-contract test that actually
    // clicks «Записи», and substitutes a trivial marker here for the same Hilt-free reason
    // `BackContractMoreScreenTest` substitutes one for `settingsScreen` below.
    //
    // `26-51`: the `Boolean` this slot now takes is whether the signed-in operator holds
    // `customer:read` — computed once below, from the [OperatorPermissions.Known] this function already
    // has in hand, and handed to [BookingsRoute] as [ago.chat.android.bookings.BookingsRoute.showConfirmedSegment].
    // A back-contract test's own substitute lambda (`{ Text("BOOKINGS_MARKER") }`) still type-checks
    // unchanged against this widened type: a single-parameter function literal that never reads its
    // parameter is ordinary Kotlin, not a test-only accommodation.
    bookingsTab: @Composable (Boolean) -> Unit = { showConfirmedSegment -> BookingsRoute(showConfirmedSegment = showConfirmedSegment) },
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
    // `26-54`: the identical "Hilt-avoidance slot" [conversationsTab] above already is — `TeamChatRoute`
    // needs `hiltViewModel()` for [ago.chat.android.team.TeamChatViewModel], and the back-contract tests
    // that visit Команда while exercising the bottom-bar/Ещё back-stack (clause 2 and clause 3) need a
    // trivial substitute here, the same way they already substitute [conversationsTab]/[settingsScreen].
    teamTab: @Composable () -> Unit = { TeamChatRoute() },
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
                bookingsTab = bookingsTab,
                settingsScreen = settingsScreen,
                teamTab = teamTab,
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
    bookingsTab: @Composable (Boolean) -> Unit,
    settingsScreen: @Composable (onBack: () -> Unit, onSiteSwitched: (String) -> Unit) -> Unit,
    teamTab: @Composable () -> Unit,
    onSiteSwitched: (String) -> Unit,
) {
    val navController = rememberNavController()
    val destinations = remember(permissions) { visibleBottomDestinations(permissions) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        // `26-28`: **this `Scaffold` owns the bottom edge and the horizontal edges; every destination
        // inside its `NavHost` owns the top edge.** Stated here because it is the only place that can
        // see both halves, and because the next screen added to that graph inherits the answer
        // silently.
        //
        // What went wrong without it. `MainActivity` calls `enableEdgeToEdge()`, so the window's
        // insets have to be applied exactly once by somebody. Material 3's `Scaffold` *reports* insets
        // through its `PaddingValues` but never *consumes* them, and every destination below draws its
        // own `Scaffold` + `TopAppBar` — and a `TopAppBar` applies `TopAppBarDefaults.windowInsets`
        // (system bars, top) of its own. So the status-bar height was applied twice: once as this
        // `Scaffold`'s content padding, once again inside each destination's own bar. The result was a
        // full status-bar height of blank surface above every screen in the shell — Диалоги, the
        // thread, Ещё, Настройки and the three placeholders alike.
        //
        // Two halves to the fix, and they are not the same mechanism:
        //
        // - **Top: not reported at all.** Dropping `WindowInsetsSides.Top` here makes the top padding
        //   zero, so each destination's own `TopAppBar` draws *into* the status bar with its own
        //   container colour behind it — which is what an edge-to-edge app is for and what the mockup
        //   shows. Padding the `NavHost` down instead would leave a band of this `Scaffold`'s
        //   background above every app bar, i.e. the same blank strip in a different colour.
        // - **Bottom and sides: reported, then consumed.** This `Scaffold` really does own the bottom
        //   edge — it is the one with the `NavigationBar` — so it keeps reporting that padding, and
        //   `consumeWindowInsets` below tells everything inside the `NavHost` that it has already been
        //   applied. Without that second call the *navigation*-bar inset double-counts exactly the way
        //   the status bar did, because a destination's own `Scaffold` would still add its own bottom
        //   inset on top of the space this one already reserved.
        contentWindowInsets =
            WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        bottomBar = {
            NavigationBar {
                // `26-23`: the mockup's `.bnav div.on .ind{background:var(--brand-tint)}` with
                // `.bnav div.on{color:var(--brand-deep)}` content — which is *not* what Material 3
                // gives by default. `NavigationBarTokens` sets `ItemActiveIndicatorColor` to
                // `SecondaryContainer` and `ItemActiveIconColor`/`ItemActiveLabelTextColor` to
                // `OnSecondaryContainer` (verified against the resolved `material3` artifact, not
                // assumed), and in this app's scheme those roles are the lavender pair, not the brand
                // one. So the three selected-state roles are supplied through the defaults API rather
                // than left to the default — the indicator itself (its pill shape, size, animation and
                // ripple) is still entirely Material 3's, never reimplemented here. Unselected content
                // is left alone: Material 3's own `onSurfaceVariant` already *is* the mockup's
                // `.bnav div{color:var(--ink-soft)}`.
                val itemColors =
                    NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
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
                        icon = {
                            Icon(
                                imageVector = destination.icon(),
                                // The tab's own visible label, reused rather than duplicated as a
                                // second string: an icon and a label that name the same destination
                                // differently is a translation bug waiting to happen.
                                contentDescription = stringResource(destination.labelRes()),
                            )
                        },
                        label = { Text(text = stringResource(destination.labelRes())) },
                        colors = itemColors,
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = BottomDestination.Conversations.route(),
            // `26-28`: `padding` then `consumeWindowInsets(padding)` — see this `Scaffold`'s own
            // `contentWindowInsets` comment above for which edge each call is answering. The order
            // matters only in that both are needed: `padding` reserves the space, `consumeWindowInsets`
            // is what stops a destination's own `Scaffold` from reserving it a second time.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(BottomDestination.Conversations.route()) { conversationsTab() }
            // `26-51`: `customer:read` alone is what earns Утверждены — see [Permission]'s own doc
            // comment table and `CalendarBookingsPage.tsx:156,182`. Computed here, once, from
            // [permissions] rather than inside [ago.chat.android.bookings.BookingsScreen], the same
            // "the caller who already has the permission set computes the Boolean" split
            // [visibleBottomDestinations] draws one level up for this whole destination's own visibility.
            composable(BottomDestination.Bookings.route()) { bookingsTab(permissions.holds(Permission.CUSTOMER_READ)) }
            composable(BottomDestination.Team.route()) { teamTab() }
            composable(BottomDestination.Analytics.route()) { AnalyticsRoute() }
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

/**
 * `26-23`: the real vector glyph for each destination, from [AgoIcons] — which is the mockup's own
 * `<symbol>` sprite, transcribed. This replaces `emoji()`, which returned a plain-text emoji per
 * destination and whose own doc comment cited `ThreadScreen`'s `"←"` as the precedent for "this app's
 * established convention": both were the same gap, and `26-23` closed both, so there is no such
 * convention left to appeal to. Kept here, alongside [route] and [labelRes], for the identical reason
 * those two are — an `ImageVector` is a UI detail the `:core:domain` enum has no business knowing.
 */
internal fun BottomDestination.icon(): ImageVector =
    when (this) {
        BottomDestination.Conversations -> AgoIcons.Chat
        BottomDestination.Bookings -> AgoIcons.Bookings
        BottomDestination.Team -> AgoIcons.Team
        BottomDestination.Analytics -> AgoIcons.Analytics
        BottomDestination.More -> AgoIcons.More
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
