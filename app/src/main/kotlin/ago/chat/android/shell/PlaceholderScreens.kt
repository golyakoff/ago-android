package ago.chat.android.shell

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * `26-16`'s own placeholder shape, brought back for `26-77`. It was deleted at `26-57` once Аналитика —
 * its last remaining caller — got a real screen, with its own doc comment stating it would stay "for
 * whichever destination is next to lose its placeholder". That destination is now `26-77`'s own new
 * Ещё rows: Автоматизация's «Готовые ответы»/«Автоответ вне смены» and Администрирование's «Операторы
 * и роли»/«Тариф и оплата» (`MoreScreen.kt`'s own `buildMoreRows()`) — real rows for named-but-unbuilt
 * features, never a dead tap and never an invented result.
 *
 * A title and one sentence, nothing that pretends otherwise: no spinner (nothing is loading), no error
 * styling (nothing failed).
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
