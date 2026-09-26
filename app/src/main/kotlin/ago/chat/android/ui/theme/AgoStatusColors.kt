package ago.chat.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The status roles Material 3's own `ColorScheme` has no clean slot for, reachable from a call site.
 *
 * Two reasons put a role here, both of them a genuine gap rather than a preference:
 *
 *  - **`warning`/`warningTint`** (`26-90`) — Material 3 has **no "warning" role at all**, so
 *    `AgoWarningLight`/`AgoWarningDark` and their tints are wired into neither `ColorScheme` in
 *    `Theme.kt`. The call site is the «Все» tab's «Не начат» status pill, drawn by the approved mockup
 *    as `.pill.warn{background:var(--warning-tint); color:var(--warning)}`.
 *  - **`dangerText`** (`26-183`) — Material 3 has exactly one danger role pair (`error`/`onError`), and
 *    `26-183` spends it on the danger *fill*: dark `error` is the solid `#B3261E` that white text sits
 *    on. That leaves on-surface danger *text* (an error message, a "withdrawn"/"unmet" label, the
 *    "cannot be undone" caption) with no role of its own — `#B3261E` is unreadable as text on this app's
 *    near-black surface. `dangerText` is that legible tone: the old coral in dark, the deep red in light
 *    (see `AgoDangerTextDark`/`AgoDangerLight`).
 *  - **`dangerIcon`** (`26-184`) — the flat status glyph's own "needs attention" state
 *    (`AgoIcons.ErrorCircle`, `SettingsScreen`'s `StatusGlyph` and `BatteryAwarenessSheet`'s header) is
 *    deliberately tinted its own colour, not `dangerText`: the author's explicit choice is a more
 *    saturated red for an *icon* than the tone tuned for on-surface *text* — see `AgoDangerIconLight`/
 *    `AgoDangerIconDark`'s own comment in `Color.kt` for why the two are allowed to diverge.
 *
 * **Why a `CompositionLocal` rather than reading the constants at the call site.** Each value is
 * light/dark-dependent, and *which* one applies is a question only [AgoChatTheme] can answer: this app
 * has a three-state theme setting (system/light/dark, `26-17`), so an operator who has forced light
 * mode on a dark-mode phone would get the wrong value from `isSystemInDarkTheme()` at the call site.
 * [AgoChatTheme] already resolves that to one `Boolean` for `MaterialTheme`'s own scheme; this provides
 * the same answer for the roles `MaterialTheme` has no room for, so the two can never disagree.
 *
 * Still deliberately narrow: two named reasons, no general-purpose "extended colours" bag. A role earns
 * a field here only when Material 3 genuinely cannot express it — as `26-90`'s own note foresaw when it
 * said this record would grow "the moment a second non-Material role earns a call site, not before".
 */
internal data class AgoStatusColors(
    val warning: Color,
    val warningTint: Color,
    val dangerText: Color,
    val dangerIcon: Color,
)

internal val LocalAgoStatusColors: ProvidableCompositionLocal<AgoStatusColors> =
    staticCompositionLocalOf {
        // The light values as the no-theme default, matching `MaterialTheme`'s own behaviour of handing
        // back a usable scheme outside a theme rather than throwing - a Compose preview or a test that
        // renders a composable without `AgoChatTheme` gets readable colours, not a crash.
        AgoStatusColors(
            warning = AgoWarningLight,
            warningTint = AgoWarningTintLight,
            dangerText = AgoDangerLight,
            dangerIcon = AgoDangerIconLight,
        )
    }

/** The status colours for the theme currently in effect. Named like `MaterialTheme.colorScheme` reads
 * at a call site, so `agoStatusColors().dangerText` sits beside `MaterialTheme.colorScheme.error`
 * without looking like a different kind of thing. */
@Composable
@ReadOnlyComposable
internal fun agoStatusColors(): AgoStatusColors = LocalAgoStatusColors.current
