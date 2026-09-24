package ago.chat.android.shell

import ago.chat.android.R
import ago.chat.android.bookings.BookingsRoute
import ago.chat.android.core.domain.navigation.BottomDestination
import ago.chat.android.core.domain.navigation.visibleBottomDestinations
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.Permission
import ago.chat.android.core.domain.permissions.holds
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.team.TeamRoute
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.components.russianPluralStringResource
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val unreadConversationsTotal by viewModel.unreadConversationsTotal.collectAsStateWithLifecycle()

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
        operatorDisplayName = identity?.displayName,
        operatorEmail = identity?.email,
        unreadConversationsTotal = unreadConversationsTotal,
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
 *
 * `26-77`: every tab slot below now takes one more argument, `onOpenSettings: () -> Unit` — each
 * top-level screen's own [ago.chat.android.ui.components.AccountAvatarAction] needs one, and only
 * [AppShellContent] holds the `NavController` that call actually navigates through (see that
 * function's own doc comment for why the wiring sits there and not in these defaults). A back-contract
 * test's own substitute lambda that ignores the new parameter still type-checks unchanged against a
 * widened function type — `bookingsTab`'s own comment below already states why for its two
 * pre-existing `Boolean`s, and one more unread parameter changes nothing about that.
 */
@Composable
internal fun AppShellScreen(
    permissions: OperatorPermissions,
    loadError: NetworkFailure?,
    activeSiteId: String?,
    hubConnectionState: OperatorHubConnectionState,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    operatorDisplayName: String? = null,
    operatorEmail: String? = null,
    // `26-46`: `null` (the default every back-contract test above still gets, unchanged) means "not
    // loaded yet" - no badge - the identical distinction `AppShellViewModel.unreadConversationsTotal`'s
    // own doc comment draws. Read by `AppShellContent` below, alone; no tab slot needs it.
    unreadConversationsTotal: Int? = null,
    onSiteSwitched: (String) -> Unit = {},
    conversationsTab: @Composable (onOpenSettings: () -> Unit) -> Unit = { onOpenSettings ->
        ConversationsTabHost(
            activeSiteId = activeSiteId,
            hubConnectionState = hubConnectionState,
            onSignOut = onSignOut,
            operatorDisplayName = operatorDisplayName,
            operatorEmail = operatorEmail,
            onOpenSettings = onOpenSettings,
            // `26-90`: the same "read [permissions] directly in the default" shape `teamTab` below
            // already uses, and safe for the identical reason it states there - [OperatorPermissions
            // .holds] answers `false` for [OperatorPermissions.Unknown], and this default is only ever
            // drawn once `AppShellScreen`'s own `when` has already matched [OperatorPermissions.Known].
            // Two separate checks, not one: `site:configure` decides whether the «Все» segment exists
            // at all, `conversation:erase` decides whether its rows swipe - the server checks them
            // separately too, and folding them here would hand the erase gesture to an administrator
            // who was never granted it.
            canSeeAllConversations = permissions.holds(Permission.SITE_CONFIGURE),
            canEraseConversations = permissions.holds(Permission.CONVERSATION_ERASE),
        )
    },
    // `26-48`: the identical "Hilt-avoidance slot" [conversationsTab] above already is, for
    // [BookingsRoute]'s own `hiltViewModel()` call - `BackContractBottomBarTest`'s own
    // `clause3_backNeverWalksThroughPreviouslyVisitedTabs` is the one back-contract test that actually
    // clicks «Записи», and substitutes a trivial marker here for the same Hilt-free reason
    // `BackContractMoreScreenTest` substitutes one for `settingsScreen` below.
    //
    // `26-51`: the first `Boolean` this slot takes is whether the signed-in operator holds
    // `customer:read` — computed once below, from the [OperatorPermissions.Known] this function already
    // has in hand, and handed to [BookingsRoute] as [ago.chat.android.bookings.BookingsRoute.showConfirmedSegment].
    // `26-52` widens this to a second `Boolean` — `calendar:configure` or `customer:read`, matching
    // [ago.chat.android.bookings.BookingsRoute.showClientsSegment]'s own doc comment — computed the
    // same way, once, at the call site below. A back-contract test's own substitute lambda
    // (`{ Text("BOOKINGS_MARKER") }`) still type-checks unchanged against this widened type: a
    // function literal that never reads its parameters is ordinary Kotlin, not a test-only
    // accommodation.
    bookingsTab: @Composable (Boolean, Boolean, Boolean, onOpenSettings: () -> Unit) -> Unit = {
        showConfirmedSegment,
        showClientsSegment,
        showServicesSegment,
        onOpenSettings,
        ->
        BookingsRoute(
            showConfirmedSegment = showConfirmedSegment,
            showClientsSegment = showClientsSegment,
            showServicesSegment = showServicesSegment,
            hubConnectionState = hubConnectionState,
            operatorDisplayName = operatorDisplayName,
            operatorEmail = operatorEmail,
            onOpenSettings = onOpenSettings,
            onSignOut = onSignOut,
        )
    },
    // `26-17`: the identical "Hilt-avoidance slot" [conversationsTab] above already is, for the same
    // reason - `BackContractMoreScreenTest` drives the real `NavHost`/`MoreScreen`/back-stack mechanics
    // with no Hilt component in play, and the default below is the one place `SettingsRoute`'s own
    // `hiltViewModel()` call would otherwise force one into existence. The two callbacks this slot is
    // handed - `onBack` (pops the new global `settings` route - see [AppShellContent]'s own doc
    // comment) and `onSiteSwitched` - are supplied at the call site inside [AppShellContent], not baked
    // into this default, because both need the `NavController` only that function owns.
    //
    // `26-77`: this slot used to be reached only from `MoreScreen`'s own `openRowId`; it is now wired
    // to a real, top-level `NavHost` route any screen's account menu can push, and `MoreScreen` no
    // longer takes this parameter at all (its own doc comment states why).
    settingsScreen: @Composable (onBack: () -> Unit, onSiteSwitched: (String) -> Unit) -> Unit = { onBack, onSwitched ->
        SettingsRoute(onBack = onBack, onSiteSwitched = onSwitched)
    },
    // `26-54`/`26-55`: the identical "Hilt-avoidance slot" [conversationsTab] above already is —
    // `TeamRoute` needs `hiltViewModel()` for both [ago.chat.android.team.TeamChatViewModel] and
    // [ago.chat.android.team.PeopleViewModel], and the back-contract tests that visit Команда while
    // exercising the bottom-bar/Ещё back-stack (clause 2 and clause 3) need a trivial substitute here,
    // the same way they already substitute [conversationsTab]/[settingsScreen]. The default reads
    // [permissions] directly — safe even while it is still [OperatorPermissions.Unknown], since
    // [OperatorPermissions.holds] answers `false` for that case and this default is only ever actually
    // *drawn* once [AppShellScreen]'s own `when` below has already matched [OperatorPermissions.Known]
    // (`AppShellContent`'s only caller).
    teamTab: @Composable (onOpenSettings: () -> Unit) -> Unit = { onOpenSettings ->
        TeamRoute(
            canManageOperators = permissions.holds(Permission.SITE_MANAGE_OPERATORS),
            hubConnectionState = hubConnectionState,
            operatorDisplayName = operatorDisplayName,
            operatorEmail = operatorEmail,
            onOpenSettings = onOpenSettings,
            onSignOut = onSignOut,
        )
    },
) {
    when (permissions) {
        OperatorPermissions.Unknown ->
            if (loadError != null) {
                PermissionsLoadFailedScreen(failure = loadError, onRetry = onRetry)
            } else {
                PermissionsLoadingScreen()
            }

        is OperatorPermissions.Known ->
            AppShellContent(
                permissions = permissions,
                hubConnectionState = hubConnectionState,
                operatorDisplayName = operatorDisplayName,
                operatorEmail = operatorEmail,
                unreadConversationsTotal = unreadConversationsTotal,
                onSignOut = onSignOut,
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
 *
 * ## `26-77`: Настройки becomes a real, top-level `NavHost` destination
 *
 * Every tab slot below is now called with an `onOpenSettings` lambda that pushes [SETTINGS_ROUTE] —
 * `navController.navigate(SETTINGS_ROUTE)`, an ordinary push with no `popUpTo`/`launchSingleTop`
 * recipe, unlike the bottom bar's own `onClick` above. That distinction is the whole answer to this
 * item's own hardest question ("how does Настройки become reachable from every tab without moving
 * anything out of each screen's own `Scaffold`"): the bottom-bar recipe exists so that switching
 * between the five *tabs* never grows the back stack past two entries; Настройки is not a tab, it is a
 * drill-in **on top of** whichever tab was current, so it wants the opposite property — a genuine push
 * that a single back pop undoes, landing exactly back on that tab. Tracing it through: open Команда
 * (stack `[conversations, team]`), open Настройки from its avatar (stack `[conversations, team,
 * settings]`), back pops `settings` → `team` is showing again, exactly the screen the operator left,
 * with its own tab still highlighted since `currentRoute` matches `team` once more. A second back pops
 * `team` → `conversations`, and a third exits — clause 3 (`AppShellScreen`'s own doc comment) extended
 * by exactly one more, perfectly ordinary layer, not re-implemented for it.
 *
 * `MoreScreen` no longer receives `settingsScreen` at all — see that file's own doc comment for why
 * Настройки leaving Ещё simplifies rather than complicates it — so this function is now the *only*
 * place [settingsScreen] is invoked.
 */
@Composable
private fun AppShellContent(
    permissions: OperatorPermissions.Known,
    hubConnectionState: OperatorHubConnectionState,
    operatorDisplayName: String?,
    operatorEmail: String?,
    unreadConversationsTotal: Int?,
    onSignOut: () -> Unit,
    conversationsTab: @Composable (onOpenSettings: () -> Unit) -> Unit,
    bookingsTab: @Composable (Boolean, Boolean, Boolean, onOpenSettings: () -> Unit) -> Unit,
    settingsScreen: @Composable (onBack: () -> Unit, onSiteSwitched: (String) -> Unit) -> Unit,
    teamTab: @Composable (onOpenSettings: () -> Unit) -> Unit,
    onSiteSwitched: (String) -> Unit,
) {
    val navController = rememberNavController()
    val destinations = remember(permissions) { visibleBottomDestinations(permissions) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // `26-18`: "A tap opens the thread, never the list" - the half of that promise this function alone
    // can keep. [ConversationsTabHost]'s own matching collector (further down this same `NavHost`, at
    // the `Conversations` route) is what actually opens the thread; this one only makes sure Диалоги is
    // the tab actually on screen when it does, in case a different tab was showing at the moment the
    // push arrived. The identical `popUpTo`/`launchSingleTop`/`restoreState` recipe the bottom bar's own
    // `onClick` below uses, so this is indistinguishable from the operator having tapped Диалоги
    // themselves - never a special-cased navigation path. A `LaunchedEffect` that never completes
    // (`collect` suspends for ever), so a *second* push arriving after this composable has been showing
    // for a while is still caught, not just the first one this composition ever saw.
    val pendingConversationOpener = rememberPendingConversationOpener()
    LaunchedEffect(pendingConversationOpener) {
        pendingConversationOpener.pendingConversationId.collect { pendingConversationId ->
            if (pendingConversationId != null) {
                navController.navigate(BottomDestination.Conversations.route()) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

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
                            // `26-46`: the mockup's `.nb` — drawn only on Диалоги, only once a real
                            // total has arrived ([unreadConversationsTotal] `null` means "not loaded
                            // yet", `AppShellViewModel.unreadConversationsTotal`'s own doc comment), and
                            // never for a genuine `0` (`docs/backlog/26-39-*.md`'s own "no count is
                            // invented, and none is drawn for a real zero either" rule, restated for a
                            // badge instead of a label).
                            val unreadCount =
                                unreadConversationsTotal
                                    ?.takeIf { it > 0 && destination == BottomDestination.Conversations }
                            if (unreadCount != null) {
                                // `unreadCount` (a fresh local `val`) is smart-cast non-null for the
                                // rest of this branch - `unreadConversationsTotal` itself, a captured
                                // parameter, would not be, which is why this is read through it rather
                                // than through that parameter directly from here on.
                                val label = stringResource(destination.labelRes())
                                val unreadClause =
                                    russianPluralStringResource(
                                        count = unreadCount.toLong(),
                                        // Reused, not duplicated: the identical clause
                                        // `ConversationListScreen`'s own `conversationRowContentDescription`
                                        // already speaks for one row's own unread count - the same
                                        // number, worded the same way, whether it is heard here or
                                        // there.
                                        one = R.string.conversation_row_unread_one,
                                        few = R.string.conversation_row_unread_few,
                                        many = R.string.conversation_row_unread_many,
                                    )
                                BadgedBox(
                                    badge = {
                                        // No explicit colours: `Badge`'s own default container/content
                                        // colours already are `colorScheme.error`/`onError` (confirmed
                                        // against the resolved `material3` artifact's own `BadgeTokens`,
                                        // not assumed - the same verification habit
                                        // `NavigationBarItemDefaults` above already follows), which is
                                        // exactly the mockup's `--danger`/`--on-danger` fill this badge
                                        // asks for.
                                        Badge(
                                            // The badge's own bare digit never becomes a second,
                                            // separately-announced TalkBack node - `VisitorAvatar`'s own
                                            // `clearAndSetSemantics {}` precedent
                                            // (`ConversationListScreen`'s doc comment on
                                            // `ConversationRow`), applied here for the identical reason:
                                            // the one sentence this whole icon speaks is set on the
                                            // `Icon` below instead.
                                            modifier = Modifier.clearAndSetSemantics {},
                                        ) {
                                            Text(text = unreadCount.toString())
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = destination.icon(),
                                        // A bare numeral announced after a tab name would be
                                        // meaningless (`docs/backlog/26-46-*.md`'s own Scope, part 4) -
                                        // one sentence naming both the tab and what the count means,
                                        // the same "state the number in words" rule
                                        // `conversationRowContentDescription` already applies to the
                                        // row's own badge.
                                        contentDescription = "$label. $unreadClause",
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = destination.icon(),
                                    // The tab's own visible label, reused rather than duplicated as a
                                    // second string: an icon and a label that name the same destination
                                    // differently is a translation bug waiting to happen.
                                    contentDescription = stringResource(destination.labelRes()),
                                )
                            }
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
            composable(BottomDestination.Conversations.route()) {
                conversationsTab { navController.navigate(SETTINGS_ROUTE) }
            }
            // `26-51`/`26-52`: `customer:read` alone earns Утверждены; `calendar:configure` or
            // `customer:read` earns Клиенты — a third, independent gate matching
            // `CalendarContactsPage.tsx:48` exactly, not the same Boolean reused (see [Permission]'s own
            // doc comment table). Computed here, once, from [permissions] rather than inside
            // [ago.chat.android.bookings.BookingsScreen], the same "the caller who already has the
            // permission set computes the Boolean" split [visibleBottomDestinations] draws one level up
            // for this whole destination's own visibility.
            composable(BottomDestination.Bookings.route()) {
                bookingsTab(
                    permissions.holds(Permission.CUSTOMER_READ),
                    permissions.holds(Permission.CALENDAR_CONFIGURE) || permissions.holds(Permission.CUSTOMER_READ),
                    // `26-96`: `calendar:configure` alone - a fourth, independent gate, not the third
                    // one reused (see [visibleBookingsTabs]' own doc comment: an operator holding only
                    // `customer:read` may read the customer base without rewriting the tenant's own
                    // service dictionary).
                    permissions.holds(Permission.CALENDAR_CONFIGURE),
                ) { navController.navigate(SETTINGS_ROUTE) }
            }
            composable(BottomDestination.Team.route()) {
                teamTab { navController.navigate(SETTINGS_ROUTE) }
            }
            composable(BottomDestination.Analytics.route()) {
                // `26-77`: no Hilt-avoidance slot exists for Аналитика — no back-contract test has ever
                // needed to visit its own content, only to navigate past it (`BackContractBottomBarTest`'s
                // own `clause3_backNeverWalksThroughPreviouslyVisitedTabs`), so this is wired directly
                // here rather than through one more slot on [AppShellScreen] nobody would ever
                // substitute.
                //
                // `26-70`: [AnalyticsTabHost] replaces the bare `AnalyticsRoute` — Аналитика now has a
                // landing screen and, behind its own overflow, the administrator reports. The host owns
                // the back contract's clause 2 for that one level (its own doc comment), exactly as
                // [MoreScreen] and [ConversationsTabHost] already do for theirs, which is why nothing
                // about *this* `NavHost` changes: back from a report is consumed one level below here.
                AnalyticsTabHost(
                    permissions = permissions,
                    hubConnectionState = hubConnectionState,
                    operatorDisplayName = operatorDisplayName,
                    operatorEmail = operatorEmail,
                    onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                    onSignOut = onSignOut,
                )
            }
            composable(BottomDestination.More.route()) {
                // `26-77`: `MoreScreen` no longer takes a `settingsScreen` slot - Настройки left Ещё
                // entirely (that file's own doc comment) - so this call site needs nothing but the same
                // account-menu inputs every other destination above already receives.
                MoreScreen(
                    hubConnectionState = hubConnectionState,
                    operatorDisplayName = operatorDisplayName,
                    operatorEmail = operatorEmail,
                    onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                    onSignOut = onSignOut,
                )
            }
            // `26-77`: the one `NavHost` route none of the five bottom-nav destinations owns - pushed by
            // any of their own account menus (`AppShellContent`'s own doc comment above states the back-
            // stack reasoning in full) rather than reached through the bottom bar's `popUpTo` recipe.
            composable(SETTINGS_ROUTE) {
                settingsScreen({ navController.popBackStack() }) { newSiteId ->
                    onSiteSwitched(newSiteId)
                    navController.navigate(BottomDestination.Conversations.route()) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    }
}

/** `26-77`: Настройки's own `NavHost` route — not a [BottomDestination] (it is reached by every
 * account menu's own push, never by a `NavigationBarItem`), so it is named here rather than added as a
 * sixth enum member `visibleBottomDestinations` would then have to be taught to always exclude. */
internal const val SETTINGS_ROUTE: String = "settings"

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
    failure: NetworkFailure,
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
                text = networkFailureText(failure),
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
