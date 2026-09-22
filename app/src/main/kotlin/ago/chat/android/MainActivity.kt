package ago.chat.android

import ago.chat.android.session.OidcConfig
import ago.chat.android.signin.SignInHost
import ago.chat.android.signin.SignInViewModel
import ago.chat.android.ui.theme.AgoChatTheme
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The only `Activity` in the app (`docs/architecture.md`, "Module layout"), and now the first one
 * with something real in it: `26-12`'s sign-in flow.
 *
 * Three Android-specific jobs live here and nowhere else, because each of them is something a
 * `ViewModel` genuinely cannot do:
 *
 * 1. **Launching the Custom Tab.** AppAuth hands back an `Intent`; only an `Activity` can start it
 *    for a result. The view model emits the intent and this collects it, so the decision to sign in
 *    stays testable and only the launch is here.
 * 2. **Receiving the redirect.** `ago-android://callback` is caught by AppAuth's own
 *    `RedirectUriReceiverActivity` (declared in the library's manifest, with the scheme supplied by
 *    `app/build.gradle.kts`'s `appAuthRedirectScheme` placeholder), which completes this
 *    `ActivityResultLauncher`. There is no callback *screen* — `scope-inventory.md`'s own reading of
 *    `/callback` as "a mechanism, not a screen" ports exactly.
 * 3. **Leaving the app** for the web console, which is an `ACTION_VIEW` and therefore a `Context`.
 */
@AndroidEntryPoint
public class MainActivity : ComponentActivity() {
    private val viewModel: SignInViewModel by viewModels()

    /** Injected rather than read from `BuildConfig` here, so one module owns "which deployment". */
    @Inject
    public lateinit var oidcConfig: OidcConfig

    private lateinit var authorizationLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        authorizationLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                // `result.data` is null when the operator dismissed the Custom Tab. That is not a
                // failure and the view model does not render it as one.
                viewModel.onAuthorizationResult(result.data)
            }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.authorizationRequests.collect { intent -> authorizationLauncher.launch(intent) }
            }
        }

        setContent {
            AgoChatTheme {
                val state by viewModel.state.collectAsState()
                val hubConnectionState by viewModel.hubConnectionState.collectAsState()
                SignInHost(
                    state = state,
                    hubConnectionState = hubConnectionState,
                    consoleUrl = oidcConfig.consoleUrl,
                    onSignIn = viewModel::beginSignIn,
                    onChooseSite = viewModel::chooseSite,
                    onRetry = viewModel::retry,
                    onSignOut = viewModel::signOut,
                    onOpenConsole = ::openInBrowser,
                )
            }
        }
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (missing: ActivityNotFoundException) {
            // A device with no browser at all, which is the only way this throws. Nothing useful to
            // offer in its place, and crashing on a link is worse than the link doing nothing — the
            // screen it sits on already says in words where the operator has to go. Swallowed here
            // rather than logged, because this class's whole neighbourhood is under a "no logging"
            // rule (`AgoAuthSession`) and one exception message is not worth carving one out for.
        }
    }
}
