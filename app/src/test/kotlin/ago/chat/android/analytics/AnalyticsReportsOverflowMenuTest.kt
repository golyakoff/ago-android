package ago.chat.android.analytics

import ago.chat.android.core.domain.navigation.AnalyticsReport
import ago.chat.android.ui.icons.AgoIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `26-244`: the Аналитика `⋮` hub's own [AnalyticsReport.iconGlyph] mapping — a plain JVM unit test, no
 * Android runner, for the identical "an [androidx.compose.ui.graphics.vector.ImageVector] is an ordinary
 * value with no framework in it" reason [ago.chat.android.ui.icons.AgoIconsTest] states. This is the
 * regression guard for this item's whole change: every report row now leads with a glyph
 * ([AnalyticsReportsOverflowMenu]'s own `leadingIcon`), and none carries the [AgoIcons.ChevronRight] the
 * pre-`26-244` menu drew as a trailing chevron.
 *
 * The render side (that the glyph sits in the `leadingIcon` slot rather than `trailingIcon`) is not
 * asserted here because it is not observable: both slots take a decorative `contentDescription = null`
 * icon, so neither is a distinct node in the semantics tree — the same reason
 * [ago.chat.android.bookings.BookingsConfigMenuTest] asserts its hub's labels and scrim but never its
 * leading glyphs. What *is* observable, and what actually changed, is the mapping this test pins.
 */
class AnalyticsReportsOverflowMenuTest {
    @Test
    fun `each report leads with its own chosen glyph`() {
        assertEquals(AgoIcons.Analytics, AnalyticsReport.Site.iconGlyph())
        assertEquals(AgoIcons.Trend, AnalyticsReport.Conversion.iconGlyph())
        assertEquals(AgoIcons.Tag, AnalyticsReport.TagBreakdown.iconGlyph())
        assertEquals(AgoIcons.Funnel, AnalyticsReport.BookingFunnel.iconGlyph())
        assertEquals(AgoIcons.Call, AnalyticsReport.PhoneReveals.iconGlyph())
    }

    /**
     * The pre-`26-244` menu drew [AgoIcons.ChevronRight] as every row's trailing icon; the whole point of
     * this item is that no row draws it any more. Held over the exhaustive `entries` list so a future
     * report added with a lazily-copied chevron mapping fails here, not just in review.
     */
    @Test
    fun `no report reuses the retired trailing chevron as its leading glyph`() {
        AnalyticsReport.entries.forEach { report ->
            assertNotEquals(
                "${report.name} must lead with a real glyph, never the retired ChevronRight",
                AgoIcons.ChevronRight,
                report.iconGlyph(),
            )
        }
    }
}
