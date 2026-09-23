package ago.chat.android.ui.components

import ago.chat.android.R
import ago.chat.android.core.domain.conversations.ElapsedLabel
import ago.chat.android.core.domain.conversations.elapsedSince
import androidx.annotation.StringRes
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

/**
 * `26-64`: Russian's own three-bucket plural rule (CLDR "ru": one/few/many), applied by hand rather than
 * through Android's `<plurals>` resource type. `<plurals>` selects its bucket from the *device's current
 * locale*'s own plural rules, not from which values folder happened to supply the string — and this app
 * ships exactly one locale (`docs/backlog/26-10-*.md`) with no `AppCompatDelegate.setApplicationLocales`
 * override, so a device set to, say, English would pick English's own two-bucket rule (one/other) and
 * hand back the "other" (many-form) string for a count like 2 or 3, which is the wrong Russian ending.
 * Doing the arithmetic here is `conversation_list_elapsed_minutes_short`'s own reasoning (see
 * [shortElapsedText]'s doc comment) taken one step further: those short units don't inflect at all, so
 * that call site got to skip this question; the full words this function serves — spoken elapsed phrases,
 * and the row's own unread count (`ConversationListScreen.kt`'s own `conversationRowContentDescription`)
 * — do inflect, and can't get away with a single fixed form.
 *
 * A public, standalone function rather than a private detail of one call site: [ConversationListScreen]'s
 * unread-count phrase needs the identical rule, and duplicating the arithmetic per caller is exactly the
 * "naive concatenation" shape this item's own Scope rejects for the description as a whole.
 */
@Composable
public fun russianPluralStringResource(
    count: Long,
    @StringRes one: Int,
    @StringRes few: Int,
    @StringRes many: Int,
): String {
    val formatArg = count.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val mod10 = count % 10
    val mod100 = count % 100
    val resId =
        when {
            mod100 in 11..14 -> many
            mod10 == 1L -> one
            mod10 in 2..4 -> few
            else -> many
        }
    return stringResource(resId, formatArg)
}
