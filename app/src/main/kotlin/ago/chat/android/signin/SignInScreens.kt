package ago.chat.android.signin

import ago.chat.android.R
import ago.chat.android.core.domain.identity.RoutingFailure
import ago.chat.android.core.domain.identity.RoutingStep
import ago.chat.android.core.domain.identity.Tenancy
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.shell.AppShellRoute
import ago.chat.android.ui.components.IdentifierText
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

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
                // `26-45`: the mockup's own gutter is 28dp, not the 24dp every other arm above shares -
                // this replaces `content` only for this one branch rather than widening it for all six
                // arms that are still centred messages, not the mockup's left-aligned block.
                SignInUiState.SignedOut ->
                    LaunchScreen(Modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp), onSignIn)
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
                    // it is a full screen with its own bottom navigation, not one more centred message.
                    //
                    // `26-16`: this is `AppShellRoute` now, not the hand-rolled two-destination
                    // placeholder `26-15` left here - see `ago.chat.android.shell.AppShellScreen`'s
                    // own doc comment for the whole shape and the back-button contract it implements.
                    AppShellRoute(
                        activeSiteId = state.activeSiteId,
                        hubConnectionState = hubConnectionState,
                        onSignOut = onSignOut,
                    )
            }
        }
    }
}

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
    // `26-45`: the one arm in this file that is not a short centred message, so it does not reach for
    // `Centred` - the mockup's `.signin` is a left-aligned block, vertically centred in its own gutter
    // (`padding:0 28px`), which `Centred`'s `horizontalAlignment = Alignment.CenterHorizontally` cannot
    // express without either widening that shared helper for one caller or adding a flag it would carry
    // forever. A plain `Column` here says exactly what this screen does and nothing more.
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        // `sign_in_wordmark` ("AGO") is a display string, not `app_name` ("AGO Chat", the launcher
        // label) - see that string's own doc comment in `strings.xml` for why the two must never merge.
        Text(
            text = stringResource(R.string.sign_in_wordmark),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.sign_in_tagline),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )
        Button(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth().height(38.dp),
            // The mockup's `.btn{border-radius:19px}` at `height:38px` is a stadium, not a Material 3
            // shape-scale radius - `AgoShapes` tops out at 16dp (`Shape.kt`) and `AgoPillShape` exists
            // but is deliberately scoped to badges only ("a pill-shaped button at this size reads as a
            // tag, not a control" - `Shape.kt`'s own comment on it), so reusing it here would be the
            // exact drift that restriction exists to prevent. `percent = 50` is the same construction,
            // applied where a full-width, fixed-height button actually calls for it.
            shape = RoundedCornerShape(percent = 50),
        ) {
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
            color = agoStatusColors().dangerText,
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
                is NetworkFailure.ServerError -> stringResource(R.string.routing_unavailable_status, reason.status)
                NetworkFailure.NoConnection -> stringResource(R.string.routing_unavailable_transport)
                NetworkFailure.Unexpected -> stringResource(R.string.routing_unavailable_malformed)
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
