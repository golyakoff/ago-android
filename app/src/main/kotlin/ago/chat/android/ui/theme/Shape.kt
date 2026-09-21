package ago.chat.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * tokens.css §"Radii" — three meaningful radii (8/12/16px) plus a pill (999px) reserved for badges
 * only. Material 3's `Shapes` has five slots; `tokens.css` has three, so the two ends of the Material 3
 * scale reuse the nearest token rather than inventing a fourth/fifth radius with no source (the same
 * "traceable, not invented" rule `Color.kt`/`Type.kt` state for their own extra Material 3 slots).
 */
public val AgoShapes: Shapes =
    Shapes(
        // --ago-radius-sm
        extraSmall = RoundedCornerShape(8.dp),
        // --ago-radius-sm
        small = RoundedCornerShape(8.dp),
        // --ago-radius-md
        medium = RoundedCornerShape(12.dp),
        // --ago-radius-lg
        large = RoundedCornerShape(16.dp),
        // --ago-radius-lg — tokens.css has nothing larger
        extraLarge = RoundedCornerShape(16.dp),
    )

/**
 * `--ago-radius-pill` — badges only, by `tokens.css`'s own restriction: "a pill-shaped button at this
 * size reads as a tag, not a control". Not part of `AgoShapes` because Material 3's `Shapes` has no
 * "pill" slot to put it in, and giving a generic slot the pill shape would let a button or a panel pick
 * it up by accident — exactly the drift `tokens.css`'s own restriction exists to prevent. A badge call
 * site reaches for this directly instead.
 */
public val AgoPillShape: RoundedCornerShape = RoundedCornerShape(percent = 50)
