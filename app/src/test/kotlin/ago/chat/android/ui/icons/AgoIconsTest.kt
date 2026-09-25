package ago.chat.android.ui.icons

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
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
    fun `i-chat is the mockup's own path data, node for node`() {
        assertTranscribed(AgoIcons.Chat, 0, "M21 12a8 8 0 0 1-8 8H7l-4 3 1-4.5A8 8 0 1 1 21 12z")
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
                AgoIcons.Clip,
                AgoIcons.Send,
                AgoIcons.Sliders,
                AgoIcons.Logout,
                AgoIcons.Call,
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

    private fun paths(icon: ImageVector): List<VectorPath> = icon.root.filterIsInstance<VectorPath>()
}
