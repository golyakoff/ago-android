package ago.chat.android

import ago.chat.android.core.domain.shortId
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * The only `Activity` in the app, and the only place Android framework and Compose UI meet
 * (`ago-android/docs/architecture.md`, "Module layout"). It exists to prove the shell — nothing
 * from `scope-inventory.md` is drawn here (`26-07`'s own Out of scope).
 */
public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgoChatTheme {
                PlaceholderScreen()
            }
        }
    }
}

@Composable
private fun AgoChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

/**
 * The one placeholder screen this item's Done-when calls for. It also exercises the real
 * `:app` -> `:core:domain` dependency (`:app` depends on both core modules directly per
 * `ago-android/docs/architecture.md`) — `shortId` is called here so that boundary is compiled
 * and run, not just declared in Gradle.
 */
@Composable
private fun PlaceholderScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold { innerPadding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "AGO Chat",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "sample-id " + shortId("3fa85f64-5717-4562-b3fc-2c963f66afa6"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaceholderScreenPreview() {
    AgoChatTheme {
        PlaceholderScreen()
    }
}
