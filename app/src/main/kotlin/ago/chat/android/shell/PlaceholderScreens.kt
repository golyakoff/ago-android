package ago.chat.android.shell

import ago.chat.android.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * `26-16`'s own Out of scope: "Any Записи, Команда or Аналитика screen. The destinations exist and
 * are empty-but-honest; their contents are later waves." Записи got its real screen at `26-48`
 * ([ago.chat.android.bookings.BookingsRoute]) and Команда at `26-54`
 * ([ago.chat.android.team.TeamChatRoute]); Аналитика is still this honest emptiness — a title, one
 * sentence saying content is not built yet, and nothing that pretends otherwise (no spinner, since
 * nothing is loading; no error styling, since nothing failed).
 *
 * `PlaceholderDestinationScreen` itself stays — a single row's worth of shared shape for whichever
 * destination is next to lose its placeholder — even though only one caller ([AnalyticsPlaceholderScreen])
 * remains.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaceholderDestinationScreen(
    title: String,
    body: String,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(topBar = { TopAppBar(title = { Text(text = title) }) }) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun AnalyticsPlaceholderScreen() {
    PlaceholderDestinationScreen(
        title = stringResource(R.string.nav_analytics),
        body = stringResource(R.string.analytics_placeholder_body),
    )
}
