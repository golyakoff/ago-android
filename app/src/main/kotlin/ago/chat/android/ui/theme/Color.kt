package ago.chat.android.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Every value below is transcribed from `ago-console/src/design/tokens.css` — read once, deliberately
 * — because `26-10`'s own Scope is explicit that this is not a second design system, only this one's
 * Android reading (`adr/0030`). Two kinds of value exist here, the same distinction `tokens.css`'s own
 * header draws for itself:
 *
 *   - CARRIED OVER — the literal hex value from `tokens.css`, unchanged, for every role that has a
 *     direct token counterpart (ink/paper/line/brand/status).
 *   - DERIVED — a Material 3 role `tokens.css` has no token for at all at all (the `surfaceContainer*`
 *     tonal-elevation family, `inversePrimary`, the `secondary`/`tertiary` roles Material 3 requires
 *     but the console's own accent-restraint design never needed). Each is built from an existing
 *     token by a rule stated in `Theme.kt`, next to where it is assigned — never a freehand guess at a
 *     Material 3 default.
 *
 * A Material 3 role that neither of the above covers — no honest token to derive it from — is left
 * unset in `Theme.kt` and Material 3's own `lightColorScheme()`/`darkColorScheme()` fills it from its
 * own defaults, recorded there per role rather than silently relied on.
 */

// --- Ink (text) — tokens.css §"Ink" -----------------------------------------------------------------
internal val AgoInkLight = Color(0xFF14141F)
internal val AgoInkSoftLight = Color(0xFF57546F)
internal val AgoInkFaintLight = Color(0xFF6B6780)

// `--ago-ink-deep` — tokens.css's own comment: not redeclared for dark, because its one job (the
// dialog backdrop/scrim) is unaffected by theme. Carried over as the single value both palettes use.
internal val AgoInkDeep = Color(0xFF0C1A19)

internal val AgoInkDark = Color(0xFFF5F4EF)
internal val AgoInkSoftDark = Color(0xFFB0B0BF)
internal val AgoInkFaintDark = Color(0xFF9191A1)

// --- Paper (backgrounds) — tokens.css §"Paper" --------------------------------------------------------
internal val AgoPaperLight = Color(0xFFFBFAF7)
internal val AgoSurfaceLight = Color(0xFFFFFFFF)
internal val AgoSurfaceRaisedLight = Color(0xFFFDFCFA)
internal val AgoSurfaceSunkenLight = Color(0xFFF1EFE9)

internal val AgoPaperDark = Color(0xFF13121C)
internal val AgoSurfaceDark = Color(0xFF22202F)
internal val AgoSurfaceRaisedDark = Color(0xFF1C1B28)
internal val AgoSurfaceSunkenDark = Color(0xFF0E0D14)

// --- Lines — tokens.css §"Lines" ------------------------------------------------------------------
internal val AgoLineLight = Color(0xFFE5E2DA)
internal val AgoLineStrongLight = Color(0xFF8D8675)
internal val AgoLineDark = Color(0xFF323040)
internal val AgoLineStrongDark = Color(0xFF787396)

// --- Brand — tokens.css §"Brand" ------------------------------------------------------------------
internal val AgoBrandLight = Color(0xFF4B3AFF)
internal val AgoBrandDeepLight = Color(0xFF3324C9)
internal val AgoBrandTintLight = Color(0xFFECEBFF)
internal val AgoLavenderLight = Color(0xFFD9D6FF)
internal val AgoLavenderDimLight = Color(0xFF8F8AC9)
internal val AgoLavenderInkLight = Color(0xFF565096)

internal val AgoBrandDark = Color(0xFF6052FF)

// Text-only in dark (tokens.css's own comment: the fill and text jobs split apart once the page goes
// near-black — see Theme.kt's onPrimary/onSecondary comments for the consequence).
internal val AgoBrandDeepDark = Color(0xFFC3BEFF)

// Fill-only in dark. Material 3's `ColorScheme` has no "hover fill" role of its own, so this is not
// wired into either scheme in `Theme.kt` — recorded here, verbatim from `tokens.css`, for the future
// call site (a pressed/hover button state) that needs it.
internal val AgoBrandHoverDark = Color(0xFF1400F5)

internal val AgoBrandTintDark = Color(0xFF1D1A38)
internal val AgoLavenderDark = Color(0xFF413A72)
internal val AgoLavenderDimDark = Color(0xFF9089C9)
internal val AgoLavenderInkDark = Color(0xFFC9C5F2)

// --- Status — tokens.css §"Status" ----------------------------------------------------------------

// Decorative dot only, in both themes (tokens.css's own note) — never wired into a `ColorScheme` role.
internal val AgoLive = Color(0xFF33D17A)

internal val AgoMintLight = Color(0xFFEAFFFB)
internal val AgoSuccessLight = Color(0xFF12684A)
internal val AgoDangerLight = Color(0xFF9F1D17)
internal val AgoDangerTintLight = Color(0xFFFDECEA)

// Material 3 has no "warning" role at all — these are not wired into either `ColorScheme` in
// `Theme.kt`. A future call site (the account-download-cap-shaped banner `tokens.css` names) reads
// these directly, the same way `tokens.css`'s own CSS consumers do today.
internal val AgoWarningLight = Color(0xFF7A4D00)
internal val AgoWarningTintLight = Color(0xFFFFF4E0)

internal val AgoMintDark = Color(0xFF0F2B22)
internal val AgoSuccessDark = Color(0xFF7FE0AF)
internal val AgoDangerDark = Color(0xFFFF8A80)
internal val AgoDangerTintDark = Color(0xFF3A1613)
internal val AgoWarningDark = Color(0xFFFFB74D)
internal val AgoWarningTintDark = Color(0xFF3A2A10)
