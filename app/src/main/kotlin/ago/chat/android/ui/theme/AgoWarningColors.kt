package ago.chat.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * `26-90`: the `--warning`/`--warning-tint` pair, reachable from a call site.
 *
 * [Color]'s own file states the standing problem: **Material 3 has no "warning" role**, so
 * `AgoWarningLight`/`AgoWarningDark` and their tints are not wired into either `ColorScheme` in
 * `Theme.kt` and its comment reserved them for "a future call site [that] reads these directly". This
 * is that call site — the «Все» tab's «Не начат» status pill, which the approved mockup draws as
 * `.pill.warn{background:var(--warning-tint); color:var(--warning)}`.
 *
 * **Why a `CompositionLocal` rather than reading the four constants at the call site.** The pair is
 * light/dark-dependent, and *which* one applies is a question only [AgoChatTheme] can answer: this app
 * has a three-state theme setting (system/light/dark, `26-17`), so an operator who has forced light
 * mode on a dark-mode phone would get the wrong pair from `isSystemInDarkTheme()` at the call site.
 * [AgoChatTheme] already resolves that to one `Boolean` for `MaterialTheme`'s own scheme; this
 * provides the same answer for the one pair `MaterialTheme` has no room for, so the two can never
 * disagree.
 *
 * Deliberately narrow: one pair, one reason, no general-purpose "extended colours" bag. The moment a
 * second non-Material role earns a call site, this becomes a record with two fields — not before.
 */
internal data class AgoWarningColors(
    val warning: Color,
    val warningTint: Color,
)

internal val LocalAgoWarningColors: ProvidableCompositionLocal<AgoWarningColors> =
    staticCompositionLocalOf {
        // The light pair as the no-theme default, matching `MaterialTheme`'s own behaviour of handing
        // back a usable scheme outside a theme rather than throwing - a Compose preview or a test that
        // renders a composable without `AgoChatTheme` gets readable colours, not a crash.
        AgoWarningColors(warning = AgoWarningLight, warningTint = AgoWarningTintLight)
    }

/** The pair for the theme currently in effect. Named like `MaterialTheme.colorScheme` reads at a call
 * site, so `agoWarningColors().warningTint` sits beside `MaterialTheme.colorScheme.tertiaryContainer`
 * without looking like a different kind of thing. */
@Composable
@ReadOnlyComposable
internal fun agoWarningColors(): AgoWarningColors = LocalAgoWarningColors.current
