package ago.chat.android.ui.icons

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `26-23`: a plain JVM unit test — no Android runner, no Robolectric — because an [ImageVector] is an
 * ordinary value with no framework in it, which is one of the reasons `AgoIcons` builds its glyphs in
 * Kotlin rather than as `res/drawable` vector XML that only an instrumented test could have read.
 *
 * **What this actually proves, and why it is worth a test at all.** `AgoIcons`'s own doc comment claims
 * the geometry is a *faithful transcription* of the mockup's sprite rather than a redrawn
 * approximation — and `docs/backlog/26-23-*.md` asks for exactly that ("using the mockup's own path
 * data, not redrawn approximations"). A claim like that is normally unverifiable by anything but an
 * eye. It is verifiable here because Compose ships its own SVG path parser: feeding [PathParser] the
 * mockup's literal `d` attribute and comparing its `PathNode` list against the one the hand-written
 * [androidx.compose.ui.graphics.vector.PathBuilder] calls produced turns "these match" into an
 * assertion. A mistyped coordinate, a swapped arc flag, or a relative command written as an absolute
 * one all fail it.
 *
 * The two icons built from SVG *primitives* rather than a `d` string (`i-cal`'s `<rect rx>` and the
 * `<circle>`s in `i-team`/`i-dots`) have no parser to check against — an SVG parser reads paths, not
 * shapes — so those are held to their structure instead: the subpath count, and the circle's own
 * two-half-arc construction. That is a weaker check, honestly weaker, and is why the assertions below
 * say so by name rather than pretending to the same standard.
 */
class AgoIconsTest {
    @Test
    fun `i-chat is the mockup's rounded chat_bubble frame and its own tail path`() {
        // `26-126`: <rect x="3.5" y="4.5" width="17" height="12" rx="3.2"/> is a primitive (see this
        // class's own doc comment) — held to its structure, the frame plus the tail = two subpaths.
        assertEquals(2, paths(AgoIcons.Chat).size)
        // <path d="M8 16.5v4l5-4"/> — the tail, held to the parser node-for-node.
        assertTranscribed(AgoIcons.Chat, 1, "M8 16.5v4l5-4")
    }

    @Test
    fun `i-cal's header rule and hanging rings are the mockup's own path data`() {
        assertTranscribed(AgoIcons.Bookings, 1, "M3 10h18M8 3v4M16 3v4")
    }

    @Test
    fun `i-team's three open strokes are the mockup's own path data`() {
        assertTranscribed(AgoIcons.Team, 1, "M3 20c0-3.2 2.7-5.2 6-5.2s6 2 6 5.2")
        assertTranscribed(AgoIcons.Team, 2, "M16 5.2A3.2 3.2 0 0 1 16 14")
        assertTranscribed(AgoIcons.Team, 3, "M18 20c0-2.4-.8-4-2-4.8")
    }

    @Test
    fun `i-chart is the mockup's own path data, node for node`() {
        assertTranscribed(AgoIcons.Analytics, 0, "M4 20V10M10 20V4M16 20v-7M22 20H2")
    }

    @Test
    fun `i-back is the mockup's own two paths, node for node`() {
        assertTranscribed(AgoIcons.Back, 0, "M19 12H5")
        assertTranscribed(AgoIcons.Back, 1, "M11 6l-6 6 6 6")
    }

    @Test
    fun `i-x is Feather's own x glyph, node for node`() {
        // `26-268` follow-up: the sheet close control's own glyph - two crossing diagonals, the identical
        // "borrow a Feather outline" precedent `AgoIcons.Call`/`AgoIcons.Tag` already establish.
        assertTranscribed(AgoIcons.Close, 0, "M18 6L6 18")
        assertTranscribed(AgoIcons.Close, 1, "M6 6L18 18")
    }

    @Test
    fun `i-clip is the mockup's own path data, node for node`() {
        assertTranscribed(
            AgoIcons.Clip,
            0,
            "M20 11l-8.5 8.5a4.5 4.5 0 0 1-6.4-6.4l9-9a3 3 0 0 1 4.3 4.3l-9 9a1.5 1.5 0 0 1-2.1-2.1l8-8",
        )
    }

    @Test
    fun `i-send is the mockup's own path data, node for node`() {
        assertTranscribed(AgoIcons.Send, 0, "M4 12l16-8-6 16-2.5-6.5L4 12z")
    }

    @Test
    fun `26-117's call icon is Feather's own phone path, node for node`() {
        // `26-126` mirrors this glyph with a wrapping group (`matrix(-1,0,0,1,24,0)`); the path nodes
        // themselves are untouched, so this transcription still holds exactly as before.
        assertTranscribed(
            AgoIcons.Call,
            0,
            "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6" +
                " 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.127.96.361 1.903.7 2.81" +
                "a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45" +
                "c.907.339 1.85.573 2.81.7A2 2 0 0 1 22 16.92z",
        )
    }

    @Test
    fun `i-sliders' four rules are the mockup's own path data, node for node`() {
        assertTranscribed(AgoIcons.Sliders, 0, "M4 7h10")
        assertTranscribed(AgoIcons.Sliders, 1, "M18 7h2")
        assertTranscribed(AgoIcons.Sliders, 2, "M4 17h4")
        assertTranscribed(AgoIcons.Sliders, 3, "M12 17h8")
    }

    @Test
    fun `i-logout's door and arrow are the mockup's own path data, node for node`() {
        assertTranscribed(AgoIcons.Logout, 0, "M9 21H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3")
        assertTranscribed(AgoIcons.Logout, 1, "M16 17l5-5-5-5")
        assertTranscribed(AgoIcons.Logout, 2, "M21 12H9")
    }

    /**
     * The weaker, structural check for the two icons whose SVG source is a primitive rather than a
     * `d` string — see this class's own doc comment for why they cannot be held to the parser.
     */
    @Test
    fun `the primitive-built icons keep the shape count their SVG source has`() {
        // <rect .../> + <path d="M3 10h18M8 3v4M16 3v4"/>
        assertEquals(2, paths(AgoIcons.Bookings).size)
        // <circle/> + three <path/>s
        assertEquals(4, paths(AgoIcons.Team).size)
        // three <circle/>s, each traced as two half-arcs and closed
        assertEquals(3, paths(AgoIcons.More).size)
        paths(AgoIcons.More).forEach { dot -> assertEquals(4, dot.pathData.size) }
        // four <path> rules (checked node-for-node above) + two <circle/> knobs
        assertEquals(6, paths(AgoIcons.Sliders).size)
        paths(AgoIcons.Sliders).drop(4).forEach { knob -> assertEquals(4, knob.pathData.size) }
    }

    /**
     * `26-176`: the five Записи `⋮` hub glyphs — held to the same structural check as
     * [AgoIcons.Bookings]/[AgoIcons.Team]/[AgoIcons.More]/[AgoIcons.Sliders] above, never
     * [assertTranscribed]: unlike every other icon in this file, these five are not a transcription of a
     * mockup's own `d` string or a borrowed Feather path — they are this app's own redrawing of a named
     * Material Symbols outlined glyph in the family's stroke treatment (each one's own doc comment in
     * `AgoIcons.kt` names which), so there is no source `d` attribute for [PathParser] to check them
     * against, only the shape this file's own construction implies.
     */
    @Test
    fun `the 26-176 config-menu icons keep the shape count their construction implies`() {
        // <circle/> (task_alt's ring) + one checkmark path
        assertEquals(2, paths(AgoIcons.Readiness).size)
        assertEquals(4, paths(AgoIcons.Readiness)[0].pathData.size)

        // <rect .../> (frame) + header rule/rings + the 3x2 date grid
        assertEquals(3, paths(AgoIcons.Calendars).size)

        // <circle/> (head) + one shoulder-arc path
        assertEquals(2, paths(AgoIcons.Masters).size)

        // three <circle/> bullets, each interleaved with its own line - six shapes, matching Sliders'
        // own "rules plus knobs" count above
        assertEquals(6, paths(AgoIcons.Services).size)
        paths(AgoIcons.Services).filterIndexed { index, _ -> index % 2 == 0 }.forEach { dot ->
            assertEquals(4, dot.pathData.size)
        }

        // <circle/> (face) + one two-segment hand path
        assertEquals(2, paths(AgoIcons.Hours).size)
    }

    /**
     * `26-244`: the three new Аналитика `⋮` hub glyphs — Feather's own `tag`, `trending-up` and `filter`,
     * borrowed verbatim the identical way [AgoIcons.Call] borrows Feather's `phone` (that glyph's own
     * `AgoIcons.kt` doc comment), so unlike the `26-176` set above these three *are* held to [PathParser]
     * node-for-node. `trending-up`/`filter` come from Feather `<polyline>`/`<polygon>`s, expanded to the
     * `d` string an implicit-`L` polyline (`filter` closed with `z`) is equivalent to.
     */
    @Test
    fun `the 26-244 analytics-menu glyphs are their Feather source paths, node for node`() {
        // <path d="M20.59 13.41...z"/> — the tag body; the punched hole is Feather's own dot <line>, held
        // structurally as a second subpath below rather than transcribed.
        assertTranscribed(
            AgoIcons.Tag,
            0,
            "M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z",
        )
        assertEquals(2, paths(AgoIcons.Tag).size)

        // <polyline points="23 6 13.5 15.5 8.5 10.5 1 18"/> and <polyline points="17 6 23 6 23 12"/>
        assertTranscribed(AgoIcons.Trend, 0, "M23 6L13.5 15.5L8.5 10.5L1 18")
        assertTranscribed(AgoIcons.Trend, 1, "M17 6L23 6L23 12")

        // <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/>
        assertTranscribed(AgoIcons.Funnel, 0, "M22 3L2 3L10 12.46L10 19L14 21L14 12.46L22 3z")
    }

    /**
     * `26-268` follow-up (author feedback 2026-09-29): `ClientDetailScreen`'s `ConfirmPhoneBanner` and
     * `ContactsScreen`'s inline phone-line warning both switched to this already-existing glyph instead of
     * the bare-stem-and-dot [AgoIcons.Exclamation], for the circled Material Symbols Outlined `error` shape
     * the author asked for — reusing [ErrorCircle] rather than hand-building a near-duplicate icon, the
     * identical "reused rather than redrawn a third time" reasoning [AgoIcons.CheckCircle]'s own doc
     * comment states for its own checkmark. Held to the same construction-implies-shape-count check
     * [AgoIcons.Readiness]/[AgoIcons.Hours] above get for their own `circle(...)` primitive, plus
     * [assertTranscribed] for the stem — the dot is checked structurally instead, the identical reason
     * [AgoIcons.Tag]'s own punched-hole dot above is never transcribed: [PathParser] reads a literal
     * `h.01` as a relative-horizontal node, where [PathBuilder.lineToRelative] (the dot's own zero-ish-length,
     * round-capped-into-a-circle convention [Exclamation]'s doc comment states) produces a relative-line
     * node instead — the same SVG point reached two different, non-`equals` ways.
     */
    @Test
    fun `AgoIcons_ErrorCircle is the circle-plus-stem-and-dot construction the phone-warning icon reuses`() {
        // <circle cx="12" cy="12" r="9"/> + the stem path + the dot path = three subpaths.
        assertEquals(3, paths(AgoIcons.ErrorCircle).size)
        // The circle, traced as two half-arcs and closed - the same structural check `AgoIcons.More`'s own
        // dots get above.
        assertEquals(4, paths(AgoIcons.ErrorCircle)[0].pathData.size)
        // M12 7v6 (the stem), held to the parser node-for-node.
        assertTranscribed(AgoIcons.ErrorCircle, 1, "M12 7v6")
        // M12 16h.01 (the dot) - a move plus one relative line, checked structurally (this class's own doc
        // comment above on why).
        assertEquals(2, paths(AgoIcons.ErrorCircle)[2].pathData.size)
    }

    /**
     * The mockup's whole icon family shares one treatment — `fill:none; stroke:currentColor;
     * stroke-width:1.8; stroke-linecap:round; stroke-linejoin:round` — and an icon that quietly drops
     * one of those reads as a different family rather than as a bug, which is why every glyph is held
     * to it here rather than each being eyeballed once.
     *
     * The stroke brush is asserted merely *present*, never for its colour:
     * [androidx.compose.material3.Icon] replaces it with its own `tint` at draw time, so the value
     * baked in is a placeholder and asserting it would pin down the one thing that genuinely does not
     * matter.
     */
    @Test
    fun `every glyph keeps the family's stroke-only treatment and the sprite's own 24-unit viewport`() {
        val icons =
            listOf(
                AgoIcons.Chat,
                AgoIcons.Bookings,
                AgoIcons.Team,
                AgoIcons.Analytics,
                AgoIcons.More,
                AgoIcons.Back,
                AgoIcons.Close,
                AgoIcons.Clip,
                AgoIcons.Send,
                AgoIcons.Sliders,
                AgoIcons.Logout,
                AgoIcons.Call,
                AgoIcons.Readiness,
                AgoIcons.Calendars,
                AgoIcons.Masters,
                AgoIcons.Services,
                AgoIcons.Hours,
                AgoIcons.Tag,
                AgoIcons.Trend,
                AgoIcons.Funnel,
                AgoIcons.ErrorCircle,
            )
        icons.forEach { icon ->
            assertEquals(icon.name, 24f, icon.viewportWidth, 0f)
            assertEquals(icon.name, 24f, icon.viewportHeight, 0f)
            paths(icon).forEach { path ->
                assertNull("${icon.name} must be stroke-only, never filled", path.fill)
                assertNotNull("${icon.name} must carry a stroke brush", path.stroke)
                assertEquals(icon.name, 1.8f, path.strokeLineWidth, 0f)
                assertEquals(icon.name, StrokeCap.Round, path.strokeLineCap)
                assertEquals(icon.name, StrokeJoin.Round, path.strokeLineJoin)
            }
        }
    }

    /** Compose's own SVG path parser against the hand-written builder calls — the whole point of this
     * class. */
    private fun assertTranscribed(
        icon: ImageVector,
        subpathIndex: Int,
        svgPathData: String,
    ) {
        val expected = PathParser().parsePathString(svgPathData).toNodes()
        val actual = paths(icon)[subpathIndex].pathData
        assertEquals("${icon.name} subpath $subpathIndex", expected, actual)
    }

    /**
     * Every [VectorPath] under an icon, in draw order, descending into nested [VectorGroup]s.
     * `26-126` wraps [AgoIcons.Call] in a mirror group (`matrix(-1,0,0,1,24,0)`), so its path is no
     * longer a direct child of `root`; recursing keeps every assertion above — its own transcription
     * included, since the group flips only the display and leaves the path's nodes untouched — working
     * for grouped and ungrouped icons alike.
     */
    private fun paths(icon: ImageVector): List<VectorPath> = collectPaths(icon.root)

    private fun collectPaths(group: VectorGroup): List<VectorPath> =
        group.flatMap { node: VectorNode ->
            when (node) {
                is VectorPath -> listOf(node)
                is VectorGroup -> collectPaths(node)
            }
        }
}
