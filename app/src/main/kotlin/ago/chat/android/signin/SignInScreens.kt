package ago.chat.android.signin

import ago.chat.android.R
import ago.chat.android.conversations.ConversationListRoute
import ago.chat.android.conversations.ConversationListViewModel
import ago.chat.android.core.domain.identity.ProbeFailure
import ago.chat.android.core.domain.identity.RoutingFailure
import ago.chat.android.core.domain.identity.RoutingStep
import ago.chat.android.core.domain.identity.Tenancy
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.thread.ThreadRoute
import ago.chat.android.ui.components.IdentifierText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * `26-12`: every surface the pre-session flow can show, in one file because they are one flow and
 * none of them is a destination (`SignInUiState`'s own remarks on why there is no navigation graph
 * here yet).
 *
 * No colour, type size or corner radius is written as a literal anywhere below: everything is read
 * from `MaterialTheme`, which is `docs/architecture.md`'s stated convention for this repository and
 * the reason `26-10` transcribed `tokens.css` into a `ColorScheme` rather than leaving screens to
 * invent their own palette.
 */
@Composable
public fun SignInHost(
    state: SignInUiState,
    hubConnectionState: OperatorHubConnectionState,
    consoleUrl: String,
    onSignIn: () -> Unit,
    onChooseSite: (String) -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    onOpenConsole: (String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold { padding ->
            val content = Modifier.fillMaxSize().padding(padding).padding(24.dp)
            when (state) {
                SignInUiState.Starting, SignInUiState.Working -> WorkingScreen(content)
                SignInUiState.SignedOut -> LaunchScreen(content, onSignIn)
                is SignInUiState.SignInFailed -> SignInFailedScreen(content, state.detail, onSignIn)
                is SignInUiState.ChooseSite -> SitePickerScreen(content, state.tenancies, onChooseSite, onSignOut)
                is SignInUiState.Unavailable -> UnavailableScreen(content, state.failure, onRetry, onSignOut)
                SignInUiState.PlatformOwnerTerminal ->
                    LinkOutScreen(
                        modifier = content,
                        title = stringResource(R.string.owner_terminal_title),
                        body = stringResource(R.string.owner_terminal_body),
                        linkLabel = stringResource(R.string.owner_terminal_open_console),
                        onOpenConsole = { onOpenConsole(consoleUrl) },
                        onSignOut = onSignOut,
                    )

                SignInUiState.Registration ->
                    LinkOutScreen(
                        modifier = content,
                        title = stringResource(R.string.registration_title),
                        body = stringResource(R.string.registration_body),
                        linkLabel = stringResource(R.string.registration_open_console),
                        onOpenConsole = { onOpenConsole(consoleUrl) },
                        onSignOut = onSignOut,
                    )

                is SignInUiState.SignedIn ->
                    // `26-14`: the placeholder this state used to render (`SignedInScreen`) is gone -
                    // this is the real screen now. It draws its own `Scaffold`/`TopAppBar` rather than
                    // reusing `content` (the `Modifier` every other, centred arm above shares), because
                    // it is a full screen with its own segmented control and two lists, not one more
                    // centred message.
                    //
                    // `26-15`: opening a row now goes somewhere - [SignedInHost] below.
                    SignedInHost(
                        activeSiteId = state.activeSiteId,
                        hubConnectionState = hubConnectionState,
                        onSignOut = onSignOut,
                    )
            }
        }
    }
}

/**
 * `26-15`: the list, and the one thread that can sit on top of it - a hand-rolled, two-destination
 * "back stack" rather than Navigation Compose. `SignInUiState`'s own doc comment states why that
 * library is not here yet: "arrives with `26-16`, which owns the bottom navigation and the
 * back-button contract... adding it here... would be a guess at that item's answer." A screen-level
 * `if` cannot make that guess wrong, because it decides nothing about the graph's eventual shape - it
 * only has to solve the one property this item's own Done-when needs now: back returns to the list
 * with its scroll position and its filters intact.
 *
 * [rememberSaveableStateHolder] is what makes that true despite the list composable being fully
 * removed from composition while the thread is open - the identical mechanism `NavHost` itself uses
 * internally for the same reason, not a workaround invented for this screen. Without it,
 * `ConversationListScreen`'s own `rememberLazyListState` would be disposed the moment `ThreadRoute`
 * replaces it in this `if`, and back would return to a list scrolled to the top. `listViewModel` is
 * hoisted here (not left to `ConversationListRoute`'s own default `hiltViewModel()`) for a second
 * reason beyond scroll: [ConversationListViewModel] is scoped to this screen's own lifetime either
 * way (Hilt's default view-model-store owner is the `Activity`, not this composable), so hoisting it
 * costs nothing and is what lets the thread branch below read the *same* already-fetched row
 * (`ConversationRowUi`) the list is showing - `ConversationSummaryDto`'s own doc comment on why the
 * thread screen is handed a row instead of re-fetching one.
 *
 * The filters (`ConversationListTab`) need no explicit handling at all: they live inside
 * [ConversationListViewModel]'s own state, which this `if` never tears down.
 */
@Composable
private fun SignedInHost(
    activeSiteId: String?,
    hubConnectionState: OperatorHubConnectionState,
    onSignOut: () -> Unit,
) {
    val listViewModel: ConversationListViewModel = hiltViewModel()
    val listState by listViewModel.state.collectAsStateWithLifecycle()
    var openConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    val stateHolder = rememberSaveableStateHolder()

    // No `BackHandler` here for "a thread is open" - `ThreadRoute` (`:app`'s own `thread` package)
    // registers its own, wrapping the identical `onBack` this composable hands it below so the system
    // back gesture and the app bar's back arrow both release the hub subscription and flush the
    // draft the same way. This composable only ever has to know "the operator asked to leave".
    val currentlyOpen = openConversationId
    if (currentlyOpen == null) {
        stateHolder.SaveableStateProvider(SAVEABLE_KEY_LIST) {
            ConversationListRoute(
                activeSiteId = activeSiteId,
                hubConnectionState = hubConnectionState,
                viewModel = listViewModel,
                onOpenConversation = { conversationId -> openConversationId = conversationId },
                onSignOut = onSignOut,
            )
        }
    } else {
        // The row the list already fetched - `null` only for the sliver of time after a process-death
        // restore before the list's own Room-backed cold start re-populates it (near-instant,
        // `ConversationListViewModel`'s own `init`). `ThreadRoute` itself needs none of these fields to
        // join, page history or send - only to render the header and decide the attach control, both
        // of which degrade to "not shown yet" rather than a crash while this is `null`.
        val row = (listState.mine + listState.waiting).firstOrNull { it.conversationId == currentlyOpen }
        stateHolder.SaveableStateProvider("$SAVEABLE_KEY_THREAD_PREFIX$currentlyOpen") {
            ThreadRoute(
                conversationId = currentlyOpen,
                visitorId = row?.visitorId ?: currentlyOpen,
                emojiCreature = row?.emojiCreature,
                emojiFood = row?.emojiFood,
                visitorName = row?.visitorName,
                hasAttachmentUploadGrant = row?.hasAttachmentUploadGrant ?: false,
                onBack = {
                    stateHolder.removeState("$SAVEABLE_KEY_THREAD_PREFIX$currentlyOpen")
                    openConversationId = null
                },
            )
        }
    }
}

private const val SAVEABLE_KEY_LIST = "conversation-list"
private const val SAVEABLE_KEY_THREAD_PREFIX = "thread:"

@Composable
private fun WorkingScreen(modifier: Modifier) {
    Centred(modifier) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.sign_in_working),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

/**
 * The launch screen. **It names no deployment** — there is deliberately no hostname, environment
 * name or API origin rendered anywhere on it (`navigation.md`, and this file has no string resource
 * that could carry one). The build variant's own name belongs in Settings → О приложении (`26-17`).
 *
 * There is also, deliberately, only one button. `navigation.md`'s graph draws a second — «Создать
 * аккаунт», reaching the registration form — and the form is explicitly out of this item's scope, so
 * a button that led nowhere would be worse than its absence. It arrives with the form.
 */
@Composable
private fun LaunchScreen(
    modifier: Modifier,
    onSignIn: () -> Unit,
) {
    Centred(modifier) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(
            text = stringResource(R.string.sign_in_tagline),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )
        Button(onClick = onSignIn) {
            Text(text = stringResource(R.string.sign_in_action))
        }
    }
}

@Composable
private fun SignInFailedScreen(
    modifier: Modifier,
    detail: String,
    onSignIn: () -> Unit,
) {
    Centred(modifier) {
        Text(
            text = stringResource(R.string.sign_in_failed_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.sign_in_failed_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(onClick = onSignIn, modifier = Modifier.padding(top = 24.dp)) {
            Text(text = stringResource(R.string.sign_in_action))
        }
    }
}

@Composable
private fun SitePickerScreen(
    modifier: Modifier,
    tenancies: List<Tenancy>,
    onChooseSite: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    Column(modifier = modifier) {
        Text(text = stringResource(R.string.site_picker_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.site_picker_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(tenancies, key = { it.siteId }) { tenancy ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onChooseSite(tenancy.siteId) }
                            .padding(vertical = 12.dp),
                ) {
                    Text(text = tenancy.siteName, style = MaterialTheme.typography.titleMedium)
                    // The site id is a GUID, so it is rendered the one way this product renders a
                    // GUID — eight monospace characters through `IdentifierText` (`26-10`), never in
                    // full and never by this screen calling `shortId` itself.
                    IdentifierText(
                        id = tenancy.siteId,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
            }
        }
        TextButton(onClick = onSignOut, modifier = Modifier.padding(top = 8.dp)) {
            Text(text = stringResource(R.string.action_sign_out))
        }
    }
}

/**
 * The retry arm. `11-17`'s whole point is that this screen exists and says something other than
 * "sign-in failed": sign-in succeeded, and the call after it did not — so it names *which question*
 * went unanswered rather than rendering one sentence for three different problems.
 */
@Composable
private fun UnavailableScreen(
    modifier: Modifier,
    failure: RoutingFailure,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    Centred(modifier) {
        Text(
            text = stringResource(R.string.routing_unavailable_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = describe(failure),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        detailOf(failure)?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(onClick = onRetry, modifier = Modifier.padding(top = 24.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
        TextButton(onClick = onSignOut) {
            Text(text = stringResource(R.string.action_sign_out))
        }
    }
}

@Composable
private fun LinkOutScreen(
    modifier: Modifier,
    title: String,
    body: String,
    linkLabel: String,
    onOpenConsole: () -> Unit,
    onSignOut: () -> Unit,
) {
    Centred(modifier) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedButton(onClick = onOpenConsole, modifier = Modifier.padding(top = 24.dp)) {
            Text(text = linkLabel)
        }
        TextButton(onClick = onSignOut) {
            Text(text = stringResource(R.string.action_sign_out))
        }
    }
}

@Composable
private fun Centred(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

@Composable
private fun describe(failure: RoutingFailure): String =
    when (failure) {
        RoutingFailure.OperatorSeatRefusedDespiteTenancies ->
            stringResource(R.string.routing_unavailable_contradiction)

        is RoutingFailure.ProbeDidNotAnswer ->
            when (failure.step) {
                RoutingStep.TENANCIES -> stringResource(R.string.routing_unavailable_tenancies)
                RoutingStep.OPERATOR_SEAT -> stringResource(R.string.routing_unavailable_operator)
                RoutingStep.OWNER_ELIGIBILITY -> stringResource(R.string.routing_unavailable_owner)
            }
    }

@Composable
private fun detailOf(failure: RoutingFailure): String? =
    when (failure) {
        RoutingFailure.OperatorSeatRefusedDespiteTenancies -> null
        is RoutingFailure.ProbeDidNotAnswer ->
            when (val reason = failure.reason) {
                is ProbeFailure.UnexpectedStatus -> stringResource(R.string.routing_unavailable_status, reason.status)
                is ProbeFailure.Transport -> stringResource(R.string.routing_unavailable_transport, reason.message)
                is ProbeFailure.Malformed -> stringResource(R.string.routing_unavailable_malformed, reason.message)
            }
    }

@Preview(showBackground = true)
@Composable
private fun LaunchScreenPreview() {
    SignInHost(
        state = SignInUiState.SignedOut,
        hubConnectionState = OperatorHubConnectionState.Disconnected,
        consoleUrl = "",
        onSignIn = {},
        onChooseSite = {},
        onRetry = {},
        onSignOut = {},
        onOpenConsole = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun SitePickerPreview() {
    SignInHost(
        state =
            SignInUiState.ChooseSite(
                listOf(
                    Tenancy("11111111-1111-1111-1111-111111111111", "Кофейня на Мира"),
                    Tenancy("22222222-2222-2222-2222-222222222222", "Ярмарка"),
                ),
            ),
        hubConnectionState = OperatorHubConnectionState.Disconnected,
        consoleUrl = "",
        onSignIn = {},
        onChooseSite = {},
        onRetry = {},
        onSignOut = {},
        onOpenConsole = {},
    )
}
