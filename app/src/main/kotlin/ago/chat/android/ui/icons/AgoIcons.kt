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
    /**
     * `26-126`: «Диалоги» and every other chat affordance (bottom-nav tab, the Записи row's chat
     * button). The round speech bubble the mockup once carried is replaced by the Material Symbols
     * `chat_bubble` shape the mini-site's own `#i-chat` was already corrected to: a rounded rectangle
     * with a small downward tail. Two subpaths — the frame as this file's `roundedRect` primitive
     * (`<rect x="3.5" y="4.5" width="17" height="12" rx="3.2"/>`) and the tail as its own stroke
     * (`<path d="M8 16.5v4l5-4"/>`) — kept stroke-only, so it stays consistent with the icon family.
     */
    public val Chat: ImageVector =
        strokeIcon(
            "AgoChat",
            // <rect x="3.5" y="4.5" width="17" height="12" rx="3.2"/>
            { roundedRect(left = 3.5f, top = 4.5f, width = 17f, height = 12f, radius = 3.2f) },
            // M8 16.5v4l5-4
            {
                moveTo(8f, 16.5f)
                verticalLineToRelative(4f)
                lineToRelative(5f, -4f)
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

    /**
     * `26-117`: the booking-detail sheet's own «Телефон» affordance and the confirmed-booking row's
     * trailing phone icon (`docs/backlog/26-117-*.md`'s own hard requirement 5: "Phone = Material
     * Symbols `call`"). Transcribed from Feather's own `phone` glyph — a handset silhouette drawn as one
     * continuous stroke, arcs and cubic curves alike — rather than Material's own filled `call` glyph,
     * for the identical reason this file's own header gives for redrawing `delete_forever` as
     * [TrashForever]: a filled Material path dropped into this family's one-continuous-1.8-stroke
     * treatment would read as a different icon set from a different app. Feather's own outline rendering
     * (`fill:none; stroke-width:2; stroke-linecap:round; stroke-linejoin:round`) already matches this
     * family's own stroke treatment almost exactly (this file's [STROKE_WIDTH] is 1.8, not 2), which is
     * why this is a transcription rather than a redraw from scratch the way [TrashForever] needed to be.
     *
     * `26-126`: mirrored horizontally (`mirrored = true`, the mockup's own `matrix(-1,0,0,1,24,0)`) so
     * the handset points as if under the *right* hand — the phone icon sits at the trailing (right) end
     * of every row it appears in. The Feather path itself is untouched; only the wrapping group flips it.
     */
    public val Call: ImageVector =
        strokeIcon(
            "AgoCall",
            // M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6
            // 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.127.96.361 1.903.7 2.81
            // a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45
            // c.907.339 1.85.573 2.81.7A2 2 0 0 1 22 16.92z
            {
                moveTo(22f, 16.92f)
                verticalLineToRelative(3f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.18f, 2f)
                arcToRelative(19.79f, 19.79f, 0f, isMoreThanHalf = false, isPositiveArc = true, -8.63f, -3.07f)
                arcToRelative(19.5f, 19.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, -6f, -6f)
                arcToRelative(19.79f, 19.79f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3.07f, -8.67f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4.11f, 2f)
                horizontalLineToRelative(3f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, 1.72f)
                curveToRelative(0.127f, 0.96f, 0.361f, 1.903f, 0.7f, 2.81f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -0.45f, 2.11f)
                lineTo(8.09f, 9.91f)
                arcToRelative(16f, 16f, 0f, isMoreThanHalf = false, isPositiveArc = false, 6f, 6f)
                lineToRelative(1.27f, -1.27f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.11f, -0.45f)
                curveToRelative(0.907f, 0.339f, 1.85f, 0.573f, 2.81f, 0.7f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 22f, 16.92f)
                close()
            },
            mirrored = true,
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

    /** `26-77` — the account menu's own «Настройки» row, its trailing chevron. [Back]'s second
     * subpath (`M11 6l-6 6 6 6`) mirrored left-to-right rather than redrawn: the identical angle,
     * pointed the other way, which is what a chevron *into* a screen means against [Back]'s chevron
     * *out of* one. */
    public val ChevronRight: ImageVector =
        strokeIcon(
            "AgoChevronRight",
            // M9 6l6 6-6 6
            {
                moveTo(9f, 6f)
                lineToRelative(6f, 6f)
                lineToRelative(-6f, 6f)
            },
        )

    /** `i-sliders` — `AccountAvatarAction`'s own «Настройки» row, its leading icon (`26-89`). Four
     * separate horizontal rules and the two circles riding on two of them, each its own subpath —
     * the same "one SVG primitive per subpath" rule every other multi-part icon in this file follows,
     * so a `close()`d circle can never bleed its join into an open line. */
    public val Sliders: ImageVector =
        strokeIcon(
            "AgoSliders",
            // M4 7h10
            {
                moveTo(4f, 7f)
                horizontalLineToRelative(10f)
            },
            // M18 7h2
            {
                moveTo(18f, 7f)
                horizontalLineToRelative(2f)
            },
            // M4 17h4
            {
                moveTo(4f, 17f)
                horizontalLineToRelative(4f)
            },
            // M12 17h8
            {
                moveTo(12f, 17f)
                horizontalLineToRelative(8f)
            },
            // <circle cx="16" cy="7" r="2"/>
            { circle(centreX = 16f, centreY = 7f, radius = 2f) },
            // <circle cx="10" cy="17" r="2"/>
            { circle(centreX = 10f, centreY = 17f, radius = 2f) },
        )

    /** `i-logout` — `AccountAvatarAction`'s own «Выйти» row, its leading icon (`26-89`). The door (an
     * open-sided rounded rectangle, its two corners each a quarter-arc matching the mockup's own
     * `a2 2 0 0 1 …` radius-2, sweep-1 arcs) and the arrow (shaft plus arrowhead) as two further
     * subpaths, exactly as the mockup's own three separate `<path>` elements are three separate
     * strokes rather than one merged shape. */
    public val Logout: ImageVector =
        strokeIcon(
            "AgoLogout",
            // M9 21H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3
            {
                moveTo(9f, 21f)
                horizontalLineTo(6f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2f, -2f)
                verticalLineTo(5f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2f, -2f)
                horizontalLineToRelative(3f)
            },
            // M16 17l5-5-5-5
            {
                moveTo(16f, 17f)
                lineToRelative(5f, -5f)
                lineToRelative(-5f, -5f)
            },
            // M21 12H9
            {
                moveTo(21f, 12f)
                horizontalLineTo(9f)
            },
        )

    /**
     * `26-128`: originally the battery-mode/autostart status glyph's own OK state, drawn *inside* this
     * app's own coloured circle badge (`SettingsScreen.kt`'s own `StatusGlyph`) — `26-184` moves that call
     * site to [CheckCircle] instead (a flat glyph with its own enclosing ring, no separate badge). [Check]
     * remains in use elsewhere at this same heavier weight — `ReadinessBody`'s met/unmet row,
     * `ContactDetailsSection`'s «Подтверждено» mark — never Material Symbols' filled `check` glyph, for
     * the identical "redraw in this family's stroke treatment, never import a filled shape" reasoning this
     * file's own header gives for [TrashForever] below. Transcribed from Feather's own `check` glyph
     * (`<polyline points="20 6 9 17 4 12"/>`) — already this file's precedent for borrowing a Feather
     * primitive verbatim, the same reasoning [Call] above states for Feather's `phone`.
     *
     * `26-137`: drawn at [STATUS_GLYPH_STROKE_WIDTH] (3.0), not the family's 1.8, because at the small
     * sizes its call sites render it the 1.8 stroke read hair-thin on a real device; the geometry is still
     * Feather's verbatim, only the weight is heavier. See that constant's own doc comment for why these
     * two glyphs, and only these two, deviate from the uniform family stroke.
     */
    public val Check: ImageVector =
        strokeIcon(
            "AgoCheck",
            // M20 6 9 17l-5-5
            {
                moveTo(20f, 6f)
                lineTo(9f, 17f)
                lineToRelative(-5f, -5f)
            },
            strokeWidth = STATUS_GLYPH_STROKE_WIDTH,
        )

    /**
     * `26-128`: originally the status glyph's own "needs attention" state — a plain exclamation mark
     * (stem + dot), matching Material Symbols' `priority_high` **in shape** (a bare mark, no enclosing
     * circle — this app's own coloured circle badge supplied that framing, so importing a glyph that drew
     * its own circle too would have doubled it). `26-184` moves that call site to [ErrorCircle] instead (a
     * flat glyph whose own ring encloses this same stem-plus-dot interior). [Exclamation] remains in use
     * elsewhere at this same heavier weight — `ReadinessBody`'s met/unmet row — and, like [Check] above,
     * transcribed rather than imported: Feather's `alert-circle` draws the identical stem-plus-dot interior
     * (`<line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>`) around its own
     * circle stroke, which this glyph keeps and that glyph's own circle drops. The dot is a real, working
     * Feather convention, not a guess: a stroked line one hundredth of a unit long still draws, because
     * [STROKE_WIDTH]'s round cap gives it a radius — the line's own zero-ish length only decides how much
     * that round cap is allowed to stretch into an oval, not whether it draws at all.
     *
     * `26-137`: drawn at [STATUS_GLYPH_STROKE_WIDTH] (3.0), not the family's 1.8, because at the small
     * sizes its call sites render it the 1.8 stroke was barely visible on a real device. The heavier weight
     * also fattens the round-cap dot (its radius is half the stroke width), so the mark reads as an
     * exclamation at that size rather than a faint tick.
     */
    public val Exclamation: ImageVector =
        strokeIcon(
            "AgoExclamation",
            // M12 8v4
            {
                moveTo(12f, 8f)
                verticalLineToRelative(4f)
            },
            // M12 16h.01
            {
                moveTo(12f, 16f)
                lineToRelative(0.01f, 0f)
            },
            strokeWidth = STATUS_GLYPH_STROKE_WIDTH,
        )

    /**
     * `26-128`: originally the first-launch battery/autostart sheet's own header glyph — the one place
     * that item kept a warning **triangle** at all, while every *circular* status badge elsewhere used
     * [Exclamation] instead ("triangle-in-a-circle looks wrong"). `26-184` retires that last call site
     * too: the sheet's header now draws [ErrorCircle], the same flat glyph the settings rows use, so no
     * triangle remains anywhere in the app. Kept here, unused, as a faithful Feather `alert-triangle`
     * transcription rather than deleted outright — a rounded-corner triangle (two arcs, three straight
     * edges) around the identical stem-plus-dot mark [Exclamation] transcribes, restated here as part of
     * one continuous icon rather than shared geometry between two `ImageVector`s.
     */
    public val Warning: ImageVector =
        strokeIcon(
            "AgoWarning",
            // M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z
            {
                moveTo(10.29f, 3.86f)
                lineTo(1.82f, 18f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 1.71f, 3f)
                horizontalLineToRelative(16.94f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 1.71f, -3f)
                lineTo(13.71f, 3.86f)
                arcToRelative(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, -3.42f, 0f)
                close()
            },
            // M12 9v4
            {
                moveTo(12f, 9f)
                verticalLineToRelative(4f)
            },
            // M12 17h.01
            {
                moveTo(12f, 17f)
                lineToRelative(0.01f, 0f)
            },
        )

    /**
     * `i-trash-forever` — the «Все» tab's own swipe-revealed destructive action (`26-90`). Material
     * Symbols' `delete_forever` **in shape** — a bin carrying a large X rather than the three vertical
     * rules of the ordinary `delete` — and **redrawn**, not imported: Material ships that glyph as a
     * filled path, and dropping a solid shape into a set whose whole identity is one 1.8-width
     * round-capped stroke family would read as a different icon from a different app, which is the same
     * reasoning this file's own header already gives for not taking `material-icons-extended`.
     *
     * Four primitives, three subpaths, transcribed from the mockup sprite's own
     * `<symbol id="i-trash-forever">` exactly as every other icon here is: the lid rule and the handle
     * and the tapering body are one continuous stroke (they meet at corners the round join is meant to
     * soften), and the X is a second — a separate `<path>` in the sprite too, because a cross drawn as
     * part of the body outline would join its two strokes at the crossing point instead of letting them
     * pass over each other.
     */
    public val TrashForever: ImageVector =
        strokeIcon(
            "AgoTrashForever",
            // M4 7h16M10 7V4.5h4V7M6 7l1 13h10l1-13
            {
                moveTo(4f, 7f)
                horizontalLineToRelative(16f)
                moveTo(10f, 7f)
                verticalLineTo(4.5f)
                horizontalLineToRelative(4f)
                verticalLineTo(7f)
                moveTo(6f, 7f)
                lineToRelative(1f, 13f)
                horizontalLineToRelative(10f)
                lineToRelative(1f, -13f)
            },
            // M9.6 11.1l4.8 4.8M14.4 11.1l-4.8 4.8
            {
                moveTo(9.6f, 11.1f)
                lineToRelative(4.8f, 4.8f)
                moveTo(14.4f, 11.1f)
                lineToRelative(-4.8f, 4.8f)
            },
        )

    /**
     * `26-176`: the Записи `⋮` hub's own «Готовность» row, its [leadingIcon][androidx.compose.material3.DropdownMenuItem].
     * Modelled on Material Symbols outlined `task_alt` — a circle with a checkmark inside — rather than
     * imported as that glyph's own filled path, the identical "redraw in this family's stroke treatment"
     * rule this file's own header states for every icon here. The checkmark is [Check]'s own two-segment
     * shape, scaled down to sit inside the circle rather than [Check] itself: at `task_alt`'s own
     * proportions the mark reads as "verified", not as a bare tick.
     */
    public val Readiness: ImageVector =
        strokeIcon(
            "AgoReadiness",
            // <circle cx="12" cy="12" r="9"/>
            { circle(centreX = 12f, centreY = 12f, radius = 9f) },
            // M8 12.5l2.5 2.5 5-5.5
            {
                moveTo(8f, 12.5f)
                lineToRelative(2.5f, 2.5f)
                lineToRelative(5f, -5.5f)
            },
        )

    /**
     * `26-176`: the Записи `⋮` hub's own «Календари» row, its leading icon. Modelled on Material Symbols
     * outlined `calendar_month` — [Bookings]'s own frame, header rule and two hanging rings, plus the one
     * thing that glyph adds and `i-cal` (drawn for the very different context of a bottom-navigation tab)
     * never needed: an interior date grid, three columns by two rows, so a calendar-configuration entry in
     * a text menu does not read as the identical glyph as the Записи tab it sits three taps away from.
     */
    public val Calendars: ImageVector =
        strokeIcon(
            "AgoCalendars",
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
            // M9 10v11M15 10v11M3 15.5h18
            {
                moveTo(9f, 10f)
                verticalLineToRelative(11f)
                moveTo(15f, 10f)
                verticalLineToRelative(11f)
                moveTo(3f, 15.5f)
                horizontalLineToRelative(18f)
            },
        )

    /**
     * `26-176`: the Записи `⋮` hub's own «Мастера» row, its leading icon. Modelled on Material Symbols
     * outlined `person` — a single head-and-shoulders figure — rather than reusing [Team] (two overlapping
     * figures, «Команда»'s own bottom-navigation glyph): Мастера is one worker dictionary row at a time,
     * not a roster of colleagues, so this is [Team]'s own head-circle-plus-shoulder-arc construction with
     * the second, half-drawn figure dropped and the remaining one centred rather than offset.
     */
    public val Masters: ImageVector =
        strokeIcon(
            "AgoMasters",
            // <circle cx="12" cy="8" r="3.2"/>
            { circle(centreX = 12f, centreY = 8f, radius = 3.2f) },
            // M5 20c0-3.86 3.13-7 7-7s7 3.14 7 7
            {
                moveTo(5f, 20f)
                curveToRelative(0f, -3.86f, 3.13f, -7f, 7f, -7f)
                reflectiveCurveToRelative(7f, 3.14f, 7f, 7f)
            },
        )

    /**
     * `26-176`: the Записи `⋮` hub's own «Услуги» row, its leading icon. Modelled on Material Symbols
     * outlined `format_list_bulleted` — three bulleted lines — rather than [Sliders] (already spoken for
     * by `AccountAvatarAction`'s own «Настройки» row): a service dictionary is a plain list of named rows,
     * which is exactly what a bulleted list glyph, not a settings-sliders one, says.
     */
    public val Services: ImageVector =
        strokeIcon(
            "AgoServices",
            // <circle cx="4" cy="7" r="1.3"/>
            { circle(centreX = 4f, centreY = 7f, radius = 1.3f) },
            // M8 7h12
            {
                moveTo(8f, 7f)
                horizontalLineToRelative(12f)
            },
            // <circle cx="4" cy="12" r="1.3"/>
            { circle(centreX = 4f, centreY = 12f, radius = 1.3f) },
            // M8 12h12
            {
                moveTo(8f, 12f)
                horizontalLineToRelative(12f)
            },
            // <circle cx="4" cy="17" r="1.3"/>
            { circle(centreX = 4f, centreY = 17f, radius = 1.3f) },
            // M8 17h12
            {
                moveTo(8f, 17f)
                horizontalLineToRelative(12f)
            },
        )

    /**
     * `26-184`: the battery/autostart status glyph's own OK state (`SettingsScreen`'s `StatusGlyph`),
     * replacing the coloured-circle-badge treatment [Check] above was drawn for. Modelled on Material
     * Symbols Outlined `check_circle` — a circle stroke enclosing a checkmark, both drawn by *this* icon
     * rather than composed from an external badge — so the glyph reads as flat and tinted at a plain
     * 24dp, with no filled backing behind it. The checkmark is not [Check]'s own path (that geometry
     * spans almost the full 24×24 viewport and would crowd a 9-radius ring); it is [Readiness]'s own
     * smaller two-segment mark, which already exists for exactly this "checkmark centred inside a
     * `circle(12, 12, 9)`" proportion — reused rather than redrawn a third time.
     */
    public val CheckCircle: ImageVector =
        strokeIcon(
            "AgoCheckCircle",
            // <circle cx="12" cy="12" r="9"/>
            { circle(centreX = 12f, centreY = 12f, radius = 9f) },
            // M8 12.5l2.5 2.5 5-5.5
            {
                moveTo(8f, 12.5f)
                lineToRelative(2.5f, 2.5f)
                lineToRelative(5f, -5.5f)
            },
        )

    /**
     * `26-184`: the battery/autostart status glyph's own "needs attention" state (`SettingsScreen`'s
     * `StatusGlyph`) and [ago.chat.android.shell.BatteryAwarenessSheet]'s own header glyph, both
     * replacing an earlier treatment this item retires — the coloured-circle-badge-plus-[Exclamation] on
     * the settings rows, and the bare warning triangle ([Warning]) on the sheet header. Modelled on
     * Material Symbols Outlined `error` — a circle stroke enclosing a stem-and-dot exclamation mark, at
     * the same `circle(12, 12, 9)` proportion [CheckCircle] above uses, so the two read as one flat
     * family at 24dp. The stem-and-dot interior is [Exclamation]'s own convention (a real stroke plus a
     * zero-ish-length, round-capped line standing in for a dot — see [Exclamation]'s own comment),
     * repositioned to sit inside this ring rather than filling the full viewport the way [Exclamation]
     * does when drawn bare.
     */
    public val ErrorCircle: ImageVector =
        strokeIcon(
            "AgoErrorCircle",
            // <circle cx="12" cy="12" r="9"/>
            { circle(centreX = 12f, centreY = 12f, radius = 9f) },
            // M12 7v6
            {
                moveTo(12f, 7f)
                verticalLineToRelative(6f)
            },
            // M12 16h.01
            {
                moveTo(12f, 16f)
                lineToRelative(0.01f, 0f)
            },
        )

    /**
     * `26-176`: the Записи `⋮` hub's own «Часы» row, its leading icon. Modelled on Material Symbols
     * outlined `schedule` — a clock face with two hands meeting at the centre, drawn as one continuous
     * stroke (12-o'clock tip down to centre, then out toward 4 o'clock) rather than two separate subpaths,
     * since the hands share their one meeting point and a `moveTo` between them would only add a seam a
     * real clock hand does not have.
     */
    public val Hours: ImageVector =
        strokeIcon(
            "AgoHours",
            // <circle cx="12" cy="12" r="9"/>
            { circle(centreX = 12f, centreY = 12f, radius = 9f) },
            // M12 8v4l3 2
            {
                moveTo(12f, 8f)
                verticalLineToRelative(4f)
                lineToRelative(3f, 2f)
            },
        )
}

/** The mockup's own `viewBox="0 0 24 24"` and `stroke-width:1.8`, stated once rather than per icon. */
private const val VIEWPORT = 24f
private const val STROKE_WIDTH = 1.8f

/**
 * `26-137`: the deliberately heavier weight [AgoIcons.Check] and [AgoIcons.Exclamation] alone are drawn at.
 * Originally because those two were the only glyphs in the set rendered *shrunk* — inside `SettingsScreen`'s
 * own ~13dp status circle rather than at the family's usual 24dp — and at that size the family's 1.8 stroke
 * came out hair-thin and barely legible on a real device (the `26-128` follow-up this item fixed). `26-184`
 * retires that particular call site ([ago.chat.android.ui.icons.AgoIcons.CheckCircle]/[AgoIcons.ErrorCircle]
 * replace it, drawn flat at the family's usual 1.8), but [Check]/[Exclamation] keep the heavier weight
 * because their remaining call sites (`ReadinessBody`'s met/unmet row, `ContactDetailsSection`'s
 * «Подтверждено» mark) render them at similarly small sizes with the identical legibility need — changing
 * it now would be an unrelated visual change to screens this item does not touch. 3.0 is the heaviest this
 * viewport carries before a check's two arms or the exclamation's stem-and-dot start to merge: pushed to
 * the maximum the style allows so the glyph reads clearly at small size, while every full-size glyph keeps
 * the uniform 1.8 the icon family's identity depends on.
 */
private const val STATUS_GLYPH_STROKE_WIDTH = 3.0f
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
    strokeWidth: Float = STROKE_WIDTH,
    mirrored: Boolean = false,
): ImageVector {
    val builder =
        ImageVector.Builder(
            name = name,
            defaultWidth = IconSize,
            defaultHeight = IconSize,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        )
    // `26-126`: a horizontal flip expressed as the mockup's own `matrix(-1,0,0,1,24,0)` — a group
    // transform (`scaleX = -1`, `translationX = 24`) wrapping the subpaths verbatim, so the glyph's
    // geometry stays a faithful transcription and only its display is mirrored. Compose composes the
    // group matrix as `translate(translationX) · scale(scaleX)`, i.e. x → 24 − x, exactly the SVG matrix.
    if (mirrored) {
        builder.addGroup(scaleX = -1f, scaleY = 1f, translationX = VIEWPORT)
    }
    subpaths.forEach { subpath ->
        builder.path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = subpath,
        )
    }
    if (mirrored) {
        builder.clearGroup()
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
