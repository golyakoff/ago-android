package ago.chat.android

import ago.chat.android.ui.components.IdentifierText
import ago.chat.android.ui.components.VisitorDisplayPrefix
import ago.chat.android.ui.theme.AgoChatTheme
import android.content.res.Configuration
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
import androidx.compose.ui.res.stringResource
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

/**
 * The one placeholder screen this item's Done-when calls for — now exercising `26-10`'s own three
 * deliverables rather than `26-07`'s bare `shortId()` call: the token-driven `AgoChatTheme`,
 * `IdentifierText` (never `.take(8)` at this call site), and `VisitorDisplayPrefix` in both its real
 * shapes — a visitor with the emoji pair and a name, and the pre-emoji-column visitor with neither
 * (`architecture.md`'s "How an identifier is rendered").
 *
 * `SAMPLE_VISITOR_ID` and the sample name/emoji below are demo data, not translatable UI text, so —
 * unlike every label around them — they are not routed through `strings.xml`: the same category a
 * demo GUID already was in `26-07`'s own scaffold (`shortId("3fa85f64-...")`), not a new exception
 * invented here. Every actual label is `stringResource(...)`.
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
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = stringResource(R.string.placeholder_message),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )

                Text(
                    text = stringResource(R.string.placeholder_identifier_label),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
                IdentifierText(id = SAMPLE_VISITOR_ID)

                Text(
                    text = stringResource(R.string.placeholder_visitor_with_pair_label),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
                VisitorDisplayPrefix(
                    emojiCreature = SAMPLE_EMOJI_CREATURE,
                    emojiFood = SAMPLE_EMOJI_FOOD,
                    visitorName = SAMPLE_VISITOR_NAME,
                    visitorId = SAMPLE_VISITOR_ID,
                )

                Text(
                    text = stringResource(R.string.placeholder_visitor_without_pair_label),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
                VisitorDisplayPrefix(
                    emojiCreature = null,
                    emojiFood = null,
                    visitorName = null,
                    visitorId = SAMPLE_VISITOR_ID,
                )
            }
        }
    }
}

private const val SAMPLE_VISITOR_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
private const val SAMPLE_EMOJI_CREATURE = "🦉" // owl
private const val SAMPLE_EMOJI_FOOD = "🍓" // strawberry
private const val SAMPLE_VISITOR_NAME = "Анна Иванова"

@Preview(showBackground = true)
@Composable
private fun PlaceholderScreenLightPreview() {
    AgoChatTheme(darkTheme = false) {
        PlaceholderScreen()
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PlaceholderScreenDarkPreview() {
    AgoChatTheme(darkTheme = true) {
        PlaceholderScreen()
    }
}
