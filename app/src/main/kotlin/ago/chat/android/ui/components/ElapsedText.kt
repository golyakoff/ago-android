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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.util.Locale

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
 * locale*'s own plural rules, not from which values folder happened to supply the string — and at the
 * time this app shipped exactly one locale (`docs/backlog/26-10-*.md`) with no
 * `AppCompatDelegate.setApplicationLocales` override, so `<plurals>` would have had nothing but Russian's
 * own device locale to read anyway.
 *
 * `26-92` lets an operator pick English at runtime, which broke that premise: the selection used to apply
 * Russian's mod-10/mod-100 grammar unconditionally, so an English "21 minutes" was chosen as if it were
 * Russian's "21 минута" (singular). `26-105` is the fix — see [localePluralResourceId] for why this stays
 * a hand-rolled branch on the active locale rather than a migration to real `<plurals>` resources.
 *
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
    val locale = LocalConfiguration.current.locales[0]
    val resId = localePluralResourceId(count, locale, one, few, many)
    return stringResource(resId, formatArg)
}

/**
 * `26-105`: the plain-Kotlin half of [russianPluralStringResource]'s bucket selection, pulled out of the
 * `@Composable` so a plain JVM `test` can drive it directly against an explicit [Locale] — this project's
 * own convention (see e.g. `SignInViewModelTest`'s doc comment) is a real assertion on a plain JVM, never
 * a Robolectric host stood up just to read `LocalConfiguration`.
 *
 * Branches on the active [locale]'s own language rather than reaching for `android.icu.text.PluralRules`
 * (bundled with the platform since API 24, comfortably under this app's own `minSdk 26` — the obvious
 * "ask the platform" alternative): that class is still an `android.*` framework type, stubbed out under
 * this module's plain-JVM `test` source set the exact same way `android.util.Log` is
 * (`app/build.gradle.kts`'s own `isReturnDefaultValues` doc comment) — a genuine plural-selection
 * assertion through it would need Robolectric, a dependency this codebase has deliberately done without
 * everywhere else. `26-91`/`26-92` ship exactly two locales, Russian and English, so hand-branching both
 * of their CLDR rules costs nothing a general library would have bought back, and this keeps the fix (and
 * its test) dependency-free.
 *
 * Migrating to real `<plurals>` resources — Android's own CLDR-correct mechanism, and this item's stated
 * preference — was rejected for a narrower reason than "a hand-rolled path is fine": every call site of
 * [russianPluralStringResource] passes three *already-named* resource ids
 * (`conversation_row_elapsed_minutes_one/_few/_many`, `conversation_row_unread_one/_few/_many`) from
 * outside `ui/components` (`ConversationListScreen.kt`, `AppShellScreen.kt`), and a `<plurals>` migration
 * would change this function's shape to a single resource id — meaning every one of those call sites would
 * need editing too, in files this item has no mandate to touch while other work is in flight there. The
 * backlog item's own Scope names this fallback explicitly: "If a hand-rolled path must stay, branch it on
 * the active locale."
 *
 * Russian (CLDR "ru"): one/few/many by the mod-10/mod-100 rule already in force before this item — the
 * "Russian output is unchanged" half of `docs/backlog/26-105-*.md`'s own Done-when. Every other supported
 * language (English) uses CLDR's own one/other rule: [one] only for a bare count of 1, [many] for
 * everything else, including 21/31/101… where Russian's rule would (correctly, for Russian) say "one".
 * Routing English's "other" through [many] rather than [few] loses nothing: `values-en/strings.xml` already
 * carries the identical English text in both, precisely because English has no third form to distinguish.
 */
internal fun localePluralResourceId(
    count: Long,
    locale: Locale,
    @StringRes one: Int,
    @StringRes few: Int,
    @StringRes many: Int,
): Int {
    if (locale.language != "ru") {
        return if (count == 1L) one else many
    }
    val mod10 = count % 10
    val mod100 = count % 100
    return when {
        mod100 in 11..14 -> many
        mod10 == 1L -> one
        mod10 in 2..4 -> few
        else -> many
    }
}
