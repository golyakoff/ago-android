package ago.chat.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Font families — tokens.css §"Type families". `tokens.css` names three real webfonts (Manrope for
 * the interface, Unbounded confined to the shell's own identity, JetBrains Mono for identifiers) and
 * is explicit about why: "every stack ends in a real system fallback, because the console must stay
 * fully usable when the webfont does not load". This item reads that *principle*, not the specific
 * font files — no Manrope/Unbounded/JetBrains Mono `.ttf` is bundled with the app (bundling real font
 * assets was not named in `26-10`'s own Scope), so the fallback is what actually ships today:
 * `FontFamily.SansSerif` stands in for Manrope/Unbounded's interface role, `FontFamily.Monospace` for
 * JetBrains Mono's identifier role. Swapping in the real files later is a `Font()`/`FontFamily()`
 * change made once in this file, not a redesign — every call site already reads `AgoFontSans` /
 * `AgoFontDisplay` / `AgoFontMono` by name, never `FontFamily.Default` directly.
 */
public val AgoFontSans: FontFamily = FontFamily.SansSerif
public val AgoFontDisplay: FontFamily = FontFamily.SansSerif
public val AgoFontMono: FontFamily = FontFamily.Monospace

// tokens.css §"Type scale" — `--ago-leading-tight` / `--ago-leading-base`.
private const val LEADING_TIGHT = 1.2
private const val LEADING_BASE = 1.5

/**
 * Material 3 asks for fifteen distinct text styles; `tokens.css` defines six sizes
 * (12/13/15/17/20/22px, mapped 1:1 to sp — Compose's `sp` is density- and font-scale-aware the way a
 * bare `px` is not, which is the whole reason it exists). Several Material 3 roles therefore share one
 * token-backed size rather than each getting an invented size with no source — the same
 * "traceable, not invented" rule `Color.kt` states for the palette roles Material 3 needs more of than
 * `tokens.css` has:
 *
 *   - display*  → 22px/20px, `AgoFontDisplay` — the shell wordmark, tokens.css's own `--ago-text-display`
 *   - headline*, titleLarge → 20px/17px, `AgoFontSans` semibold — the page title and panel headings
 *   - titleMedium/Small, label* → 15px/13px/12px, `AgoFontSans` medium — form/meta text with emphasis
 *   - body* → 15px/13px, `AgoFontSans` regular — `--ago-text-base`/`--ago-text-sm`
 */
private fun agoTextStyle(
    fontFamily: FontFamily,
    fontWeight: FontWeight,
    sizeSp: Int,
    leading: Double,
): TextStyle =
    TextStyle(
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * leading).sp,
    )

public val AgoTypography: Typography =
    Typography(
        displayLarge = agoTextStyle(AgoFontDisplay, FontWeight.SemiBold, 22, LEADING_TIGHT),
        displayMedium = agoTextStyle(AgoFontDisplay, FontWeight.SemiBold, 22, LEADING_TIGHT),
        displaySmall = agoTextStyle(AgoFontDisplay, FontWeight.SemiBold, 20, LEADING_TIGHT),
        headlineLarge = agoTextStyle(AgoFontSans, FontWeight.SemiBold, 20, LEADING_TIGHT),
        headlineMedium = agoTextStyle(AgoFontSans, FontWeight.SemiBold, 20, LEADING_TIGHT),
        headlineSmall = agoTextStyle(AgoFontSans, FontWeight.SemiBold, 17, LEADING_TIGHT),
        titleLarge = agoTextStyle(AgoFontSans, FontWeight.SemiBold, 17, LEADING_TIGHT),
        titleMedium = agoTextStyle(AgoFontSans, FontWeight.Medium, 15, LEADING_BASE),
        titleSmall = agoTextStyle(AgoFontSans, FontWeight.Medium, 13, LEADING_BASE),
        bodyLarge = agoTextStyle(AgoFontSans, FontWeight.Normal, 15, LEADING_BASE),
        bodyMedium = agoTextStyle(AgoFontSans, FontWeight.Normal, 15, LEADING_BASE),
        bodySmall = agoTextStyle(AgoFontSans, FontWeight.Normal, 13, LEADING_BASE),
        labelLarge = agoTextStyle(AgoFontSans, FontWeight.Medium, 13, LEADING_BASE),
        labelMedium = agoTextStyle(AgoFontSans, FontWeight.Medium, 12, LEADING_BASE),
        labelSmall = agoTextStyle(AgoFontSans, FontWeight.Medium, 12, LEADING_BASE),
    )
