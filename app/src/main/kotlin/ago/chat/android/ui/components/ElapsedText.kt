package ago.chat.android.ui.components

import ago.chat.android.R
import ago.chat.android.core.domain.conversations.ElapsedLabel
import ago.chat.android.core.domain.conversations.elapsedSince
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import java.time.OffsetDateTime

/**
 * `26-30`: the mockup's own short elapsed form — «4 ч» / «20 мин» / «2 д», no «Открыт»/«Ждёт» prefix of
 * any kind. Moved here from `ConversationListScreen` (`26-40`) now that the thread app-bar's subtitle
 * is a second caller — nothing about the wording or the bucketing changed in the move, only its
 * address, so both screens read the identical formatter rather than the thread screen growing its own
 * copy (`docs/backlog/26-40-*.md`'s own Scope: "reuse that formatter rather than writing a second one").
 *
 * Genuinely prefix-free: Russian's short time units («ч», «мин», «д») do not inflect by count the way
 * the full words «час»/«часа»/«часов» do, so this reads a plain formatted string resource rather than
 * [androidx.compose.ui.res.pluralStringResource] — there is no plural rule left to apply once the unit
 * itself stopped needing one.
 */
@Composable
public fun shortElapsedText(
    timestamp: String,
    now: OffsetDateTime,
): String =
    when (val elapsed = elapsedSince(timestamp, now)) {
        is ElapsedLabel.Minutes ->
            stringResource(R.string.conversation_list_elapsed_minutes_short, elapsed.value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())

        is ElapsedLabel.Hours ->
            stringResource(R.string.conversation_list_elapsed_hours_short, elapsed.value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())

        is ElapsedLabel.Days ->
            stringResource(R.string.conversation_list_elapsed_days_short, elapsed.value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())

        ElapsedLabel.Unknown -> stringResource(R.string.conversation_list_elapsed_unknown)
    }

/**
 * The one clock read a screen rendering [shortElapsedText] values needs to make — `ago-console`'s own
 * `useNow` hook, restated, and (`26-40`) shared rather than each screen hand-rolling its own
 * `while (true) { delay(...); now = ... }` loop. Coarser than a second and finer than a minute, matching
 * `ConversationListScreen`'s own original `ELAPSED_TICK_MILLIS` reasoning: every elapsed-time label on
 * screen re-renders together, on one clock read, rather than each one reading
 * [OffsetDateTime.now] on its own recomposition schedule.
 */
@Composable
public fun rememberTickingNow(intervalMillis: Long = ELAPSED_TICK_MILLIS): OffsetDateTime {
    var now by remember { mutableStateOf(OffsetDateTime.now()) }
    LaunchedEffect(intervalMillis) {
        while (true) {
            delay(intervalMillis)
            now = OffsetDateTime.now()
        }
    }
    return now
}

private const val ELAPSED_TICK_MILLIS = 30_000L
