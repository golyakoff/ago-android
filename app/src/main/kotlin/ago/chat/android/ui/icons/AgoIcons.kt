package ago.chat.android.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * `26-23`: the app's own icon set, transcribed from the approved mockup's `<symbol>` sprite
 * (`docs/backlog/26-23-*.md` carries the same path data this file is built from, verbatim). Every glyph
 * here replaces a plain-text emoji or arrow character that a previous item drew as [androidx.compose.material3.Text] —
 * `BottomDestination.emoji()`, `ThreadScreen`'s `"←"`, the composer's `"📎"`.
 *
 * **Why hand-built [ImageVector]s rather than `material-icons-extended` or bundled vector drawables.**
 * The mockup's icon set is not Material's: it is one stroke-only family drawn at a uniform 1.8 width
 * with round caps and joins, and reaching for `androidx.compose.material:material-icons-extended`
 * would mean shipping ~5 MB of *filled* Material glyphs that then still would not match the design the
 * author approved. A vector drawable under `res/drawable` per icon would match, but splits the geometry away
 * from the code that names it and gives every icon an `R.drawable` id that only Android can resolve —
 * where an `ImageVector` is an ordinary value, previewable, and testable from a plain unit test. The
 * geometry below is a *faithful* transcription, never an approximation: each SVG primitive maps to the
 * [PathBuilder] call with the same semantics (`a` → `arcToRelative`, `s` → `reflectiveCurveToRelative`,
 * a `<circle>` → two half-arcs, a `<rect rx>` → four corner arcs).
 *
 * **Why the stroke is a literal [Color.Black] here and not a theme colour.** This is the one place in
 * the app where a `Color(...)` literal is correct rather than a violation of `docs/architecture.md`'s
 * "no literal colour at a call site" rule: [androidx.compose.material3.Icon] paints its `tint` as a
 * `ColorFilter` over the whole vector, so whatever colour is baked in is *replaced* at draw time. It is
 * a placeholder the renderer never shows, not a design decision — which is exactly why every call site
 * must go through `Icon(...)` (which tints) rather than `Image(...)` (which does not).
 */
public object AgoIcons {
    /** `i-chat` — «Диалоги». A speech bubble with a tail, one continuous subpath. */
    public val Chat: ImageVector =
        strokeIcon(
            "AgoChat",
            // M21 12a8 8 0 0 1-8 8H7l-4 3 1-4.5A8 8 0 1 1 21 12z
            {
                moveTo(21f, 12f)
                arcToRelative(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -8f, 8f)
                horizontalLineTo(7f)
                lineToRelative(-4f, 3f)
                lineToRelative(1f, -4.5f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 21f, 12f)
                close()
            },
        )

    /** `i-cal` — «Записи». A rounded calendar frame, its header rule, and two hanging rings. */
    public val Bookings: ImageVector =
        strokeIcon(
            "AgoBookings",
            // <rect x="3" y="5" width="18" height="16" rx="2.5"/>
            { roundedRect(left = 3f, top = 5f, width = 18f, height = 16f, radius = 2.5f) },
            // M3 10h18M8 3v4M16 3v4
            {
                moveTo(3f, 10f)
                horizontalLineToRelative(18f)
                moveTo(8f, 3f)
                verticalLineToRelative(4f)
                moveTo(16f, 3f)
                verticalLineToRelative(4f)
            },
        )

    /** `i-team` — «Команда». One full figure (head + shoulders) and a second, half-drawn behind it. */
    public val Team: ImageVector =
        strokeIcon(
            "AgoTeam",
            // <circle cx="9" cy="8" r="3.2"/>
            { circle(centreX = 9f, centreY = 8f, radius = 3.2f) },
            // M3 20c0-3.2 2.7-5.2 6-5.2s6 2 6 5.2
            {
                moveTo(3f, 20f)
                curveToRelative(0f, -3.2f, 2.7f, -5.2f, 6f, -5.2f)
                reflectiveCurveToRelative(6f, 2f, 6f, 5.2f)
            },
            // M16 5.2A3.2 3.2 0 0 1 16 14
            {
                moveTo(16f, 5.2f)
                arcTo(3.2f, 3.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 16f, 14f)
            },
            // M18 20c0-2.4-.8-4-2-4.8
            {
                moveTo(18f, 20f)
                curveToRelative(0f, -2.4f, -0.8f, -4f, -2f, -4.8f)
            },
        )

    /** `i-chart` — «Аналитика». Three bars standing on one baseline. */
    public val Analytics: ImageVector =
        strokeIcon(
            "AgoAnalytics",
            // M4 20V10M10 20V4M16 20v-7M22 20H2
            {
                moveTo(4f, 20f)
                verticalLineTo(10f)
                moveTo(10f, 20f)
                verticalLineTo(4f)
                moveTo(16f, 20f)
                verticalLineToRelative(-7f)
                moveTo(22f, 20f)
                horizontalLineTo(2f)
            },
        )

    /** `i-dots` — «Ещё». Three dots, each a closed circle rather than a filled shape, so the whole set
     * keeps the family's stroke-only treatment at any tint. */
    public val More: ImageVector =
        strokeIcon(
            "AgoMore",
            { circle(centreX = 5f, centreY = 12f, radius = 1.6f) },
            { circle(centreX = 12f, centreY = 12f, radius = 1.6f) },
            { circle(centreX = 19f, centreY = 12f, radius = 1.6f) },
        )

    /**
     * `26-32` — the conversation list's overflow control. The mockup draws the header's overflow as a
     * **vertical** ⋮ while its own `i-dots` sprite, reused for «Ещё» above, is horizontal. Rather than
     * redraw one, this is that exact sprite transposed: the same three circles, the same radius, `cx`
     * and `cy` swapped. Nothing here is a chosen number — every value is [More]'s, in the other axis.
     *
     * Deliberately not `Icons.Default.MoreVert`: `26-23` replaced this app's whole icon set with the
     * mockup's own stroke-drawn family precisely so one Material default would not sit among them
     * looking almost right.
     */
    public val MoreVertical: ImageVector =
        strokeIcon(
            "AgoMoreVertical",
            { circle(centreX = 12f, centreY = 5f, radius = 1.6f) },
            { circle(centreX = 12f, centreY = 12f, radius = 1.6f) },
            { circle(centreX = 12f, centreY = 19f, radius = 1.6f) },
        )

    /** `i-back` — the thread screen's app-bar back control, replacing `ThreadScreen`'s literal `"←"`. */
    public val Back: ImageVector =
        strokeIcon(
            "AgoBack",
            // M19 12H5
            {
                moveTo(19f, 12f)
                horizontalLineTo(5f)
            },
            // M11 6l-6 6 6 6
            {
                moveTo(11f, 6f)
                lineToRelative(-6f, 6f)
                lineToRelative(6f, 6f)
            },
        )

    /** `i-clip` — the composer's attach control, replacing its literal `"📎"`. */
    public val Clip: ImageVector =
        strokeIcon(
            "AgoClip",
            // M20 11l-8.5 8.5a4.5 4.5 0 0 1-6.4-6.4l9-9a3 3 0 0 1 4.3 4.3l-9 9a1.5 1.5 0 0 1-2.1-2.1l8-8
            {
                moveTo(20f, 11f)
                lineToRelative(-8.5f, 8.5f)
                arcToRelative(4.5f, 4.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, -6.4f, -6.4f)
                lineToRelative(9f, -9f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4.3f, 4.3f)
                lineToRelative(-9f, 9f)
                arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.1f, -2.1f)
                lineToRelative(8f, -8f)
            },
        )

    /** `i-send` — the composer's send control. */
    public val Send: ImageVector =
        strokeIcon(
            "AgoSend",
            // M4 12l16-8-6 16-2.5-6.5L4 12z
            {
                moveTo(4f, 12f)
                lineToRelative(16f, -8f)
                lineToRelative(-6f, 16f)
                lineToRelative(-2.5f, -6.5f)
                lineTo(4f, 12f)
                close()
            },
        )
}

/** The mockup's own `viewBox="0 0 24 24"` and `stroke-width:1.8`, stated once rather than per icon. */
private const val VIEWPORT = 24f
private const val STROKE_WIDTH = 1.8f
private val IconSize = 24.dp

/**
 * Builds one icon from one or more subpaths. Each subpath becomes its own `path` node with the
 * family's shared stroke treatment (`fill:none; stroke-width:1.8; stroke-linecap:round;
 * stroke-linejoin:round`) — a separate node per SVG primitive rather than one node with many
 * subpaths, so a `close()`d shape (a circle, the calendar frame) cannot bleed its join into the open
 * stroke that follows it.
 */
private fun strokeIcon(
    name: String,
    vararg subpaths: PathBuilder.() -> Unit,
): ImageVector {
    val builder =
        ImageVector.Builder(
            name = name,
            defaultWidth = IconSize,
            defaultHeight = IconSize,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        )
    subpaths.forEach { subpath ->
        builder.path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = subpath,
        )
    }
    return builder.build()
}

/** SVG `<circle cx cy r>`, traced as the two half-arcs a path can express — the faithful translation
 * of the primitive, not a polygon approximating it. */
private fun PathBuilder.circle(
    centreX: Float,
    centreY: Float,
    radius: Float,
) {
    moveTo(centreX - radius, centreY)
    arcToRelative(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, radius * 2f, 0f)
    arcToRelative(radius, radius, 0f, isMoreThanHalf = true, isPositiveArc = true, -radius * 2f, 0f)
    close()
}

/** SVG `<rect x y width height rx>` with a uniform corner radius, traced as four edges joined by four
 * quarter-arcs — the shape `i-cal`'s calendar frame is drawn from. */
private fun PathBuilder.roundedRect(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    radius: Float,
) {
    val right = left + width
    val bottom = top + height
    moveTo(left + radius, top)
    horizontalLineTo(right - radius)
    arcToRelative(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, radius, radius)
    verticalLineTo(bottom - radius)
    arcToRelative(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, -radius, radius)
    horizontalLineTo(left + radius)
    arcToRelative(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, -radius, -radius)
    verticalLineTo(top + radius)
    arcToRelative(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, radius, -radius)
    close()
}
