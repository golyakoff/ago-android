package ago.chat.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * DERIVED roles (see `Color.kt`'s own header for the CARRIED OVER / DERIVED distinction) — Material 3
 * needs more colour slots than `tokens.css` has tokens for. Each one below is built from an existing
 * token by the rule in its own comment; nothing here is an invented value with no source.
 */
private val AgoLightColorScheme =
    lightColorScheme(
        primary = AgoBrandLight,
        // --ago-brand measures 6.27:1 with white on top (tokens.css)
        onPrimary = Color.White,
        primaryContainer = AgoBrandTintLight,
        onPrimaryContainer = AgoBrandDeepLight,
        // DERIVED: the tint already reads on top of `--ago-brand-deep` (8.00:1, tokens.css) — the same
        // relationship `inversePrimary` needs against a primary-toned surface.
        inversePrimary = AgoBrandTintLight,
        secondary = AgoLavenderInkLight,
        onSecondary = Color.White,
        secondaryContainer = AgoLavenderLight,
        onSecondaryContainer = AgoLavenderInkLight,
        tertiary = AgoSuccessLight,
        onTertiary = Color.White,
        tertiaryContainer = AgoMintLight,
        onTertiaryContainer = AgoSuccessLight,
        error = AgoDangerLight,
        onError = Color.White,
        errorContainer = AgoDangerTintLight,
        onErrorContainer = AgoDangerLight,
        background = AgoPaperLight,
        onBackground = AgoInkLight,
        surface = AgoSurfaceLight,
        onSurface = AgoInkLight,
        surfaceVariant = AgoSurfaceSunkenLight,
        onSurfaceVariant = AgoInkSoftLight,
        surfaceTint = AgoBrandLight,
        // clears WCAG 1.4.11's 3:1 boundary bar (tokens.css)
        outline = AgoLineStrongLight,
        // decorative separator only — tokens.css's own note
        outlineVariant = AgoLineLight,
        // `--ago-ink-deep` already *is* the dialog backdrop/scrim colour in tokens.css, unchanged by
        // theme — carried over as-is rather than derived.
        scrim = AgoInkDeep,
        // DERIVED: ink and paper "trade roles" under inversion — tokens.css's own dark-theme header
        // comment — so the light theme's inverse surface is simply the dark theme's own ink/paper pair.
        inverseSurface = AgoInkLight,
        inverseOnSurface = AgoPaperLight,
        // DERIVED: tokens.css has four background tones, darkest to lightest —
        // surface-sunken < paper < surface-raised < surface. Material 3's six-step surfaceContainer*
        // tonal ramp reuses that exact order, stretched across six slots, rather than inventing new
        // tones with no source.
        surfaceDim = AgoSurfaceSunkenLight,
        surfaceBright = AgoSurfaceLight,
        surfaceContainerLowest = AgoPaperLight,
        surfaceContainerLow = AgoSurfaceRaisedLight,
        surfaceContainer = AgoSurfaceRaisedLight,
        surfaceContainerHigh = AgoSurfaceLight,
        surfaceContainerHighest = AgoSurfaceLight,
    )

private val AgoDarkColorScheme =
    darkColorScheme(
        primary = AgoBrandDark,
        // tokens.css's own dark-mode comment: `--ago-brand`'s fill role clears 4.5:1 with white on top.
        onPrimary = Color.White,
        primaryContainer = AgoBrandTintDark,
        onPrimaryContainer = AgoBrandDeepDark,
        inversePrimary = AgoBrandTintDark,
        secondary = AgoLavenderInkDark,
        // `--ago-lavender-ink` is itself a LIGHT tone in dark mode (#c9c5f2, 9.69:1 on --ago-surface) —
        // it needs dark ink on top, not white, unlike its light-mode counterpart.
        onSecondary = AgoInkDark,
        secondaryContainer = AgoLavenderDark,
        onSecondaryContainer = AgoLavenderInkDark,
        tertiary = AgoSuccessDark,
        // `--ago-success` is also a light tone in dark mode (#7fe0af)
        onTertiary = AgoInkDark,
        tertiaryContainer = AgoMintDark,
        onTertiaryContainer = AgoSuccessDark,
        // `26-183`: `error` is the danger FILL now (`AgoDangerDark` = #B3261E), and everything drawn on
        // top of it — badge digit, swipe-to-erase caption, the restrict/erase confirm buttons — is pure
        // white. On-surface danger *text* does not read this role; it reads `AgoStatusColors.dangerText`
        // (see `AgoDangerTextDark`'s own comment for why one hex cannot be both).
        error = AgoDangerDark,
        onError = Color.White,
        // `26-183`: the tinted danger banner (invite-send-failed) stays maroon-filled. Its on-text was
        // `AgoDangerDark`, which is now the #B3261E fill and would be unreadable on this maroon (≈1:1) —
        // so it moves to the legible light `AgoDangerTextDark` (≈6.7:1), the Material 3 idiom of a light
        // tonal on-colour for a soft *container* (white is reserved for the solid `error` fills above),
        // and the same coral-on-maroon the design source draws for its own `.card.dangerband`.
        errorContainer = AgoDangerTintDark,
        onErrorContainer = AgoDangerTextDark,
        background = AgoPaperDark,
        onBackground = AgoInkDark,
        surface = AgoSurfaceDark,
        onSurface = AgoInkDark,
        surfaceVariant = AgoSurfaceSunkenDark,
        onSurfaceVariant = AgoInkSoftDark,
        surfaceTint = AgoBrandDark,
        outline = AgoLineStrongDark,
        outlineVariant = AgoLineDark,
        // unchanged by theme — see the light scheme's own comment
        scrim = AgoInkDeep,
        inverseSurface = AgoInkDark,
        inverseOnSurface = AgoPaperDark,
        surfaceDim = AgoSurfaceSunkenDark,
        surfaceBright = AgoSurfaceDark,
        surfaceContainerLowest = AgoPaperDark,
        surfaceContainerLow = AgoSurfaceRaisedDark,
        surfaceContainer = AgoSurfaceRaisedDark,
        surfaceContainerHigh = AgoSurfaceDark,
        surfaceContainerHighest = AgoSurfaceDark,
    )

/**
 * The app's one entry point into the design system. Wraps Material 3's own `MaterialTheme` with the
 * token-derived colour scheme, type scale and shape set above/`Type.kt`/`Shape.kt` — every screen
 * reads `MaterialTheme.colorScheme`/`.typography`/`.shapes`, never a literal colour or size at its own
 * call site (`architecture.md`'s enforcement note: no lint rule for this exists cheaply today, so it
 * is a written convention instead — see that document for the reasoning).
 *
 * **Android 12+ dynamic colour is deliberately not used.** This is `26-10`'s own decision to make, not
 * a placeholder left for later — recorded in full in `ago-android/docs/architecture.md`, "Dynamic
 * colour"; in short: the console has no per-user wallpaper-driven theming at all, and a B2B operator
 * tool benefits more from one consistent brand identity across web and mobile than from matching
 * whatever wallpaper happens to be on an operator's phone that day. `AgoLightColorScheme`/
 * `AgoDarkColorScheme` above are used unconditionally, on every OS version — `dynamicLightColorScheme`/
 * `dynamicDarkColorScheme` are never called anywhere in this module.
 *
 * `darkTheme` defaults to the OS setting (`isSystemInDarkTheme()`) — the "system" state of the
 * console's own three-state system/light/dark activation. `26-17` is the "future settings screen" this
 * doc comment used to name as owing the other two states: [ago.chat.android.MainActivity] now resolves
 * [ThemeMode] (read from [ThemePreferences], the settings screen's own store) into a `Boolean` before
 * calling this function, exactly the way `useTheme`/`ThemeToggle` wire it for the console — this
 * function itself needed no change at all, since it already accepted `darkTheme` as a plain parameter.
 */
@Composable
public fun AgoChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) AgoDarkColorScheme else AgoLightColorScheme
    // `26-90`/`26-183`: the roles Material 3's `ColorScheme` has no clean slot for, resolved from the
    // same `darkTheme` answer the scheme above is - see `AgoStatusColors`' own doc comment for why this
    // cannot be decided at the call site.
    val statusColors =
        if (darkTheme) {
            AgoStatusColors(
                warning = AgoWarningDark,
                warningTint = AgoWarningTintDark,
                dangerText = AgoDangerTextDark,
            )
        } else {
            AgoStatusColors(
                warning = AgoWarningLight,
                warningTint = AgoWarningTintLight,
                dangerText = AgoDangerLight,
            )
        }
    CompositionLocalProvider(LocalAgoStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AgoTypography,
            shapes = AgoShapes,
            content = content,
        )
    }
}
