package ago.chat.android.core.domain.navigation

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.Permission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-70`: the whole of "who sees the `⋮` on Аналитика, and what is behind it" — the half of that
 * Done-when that needs no device, deliberately expressed as a pure function so it does not
 * (`visibleAnalyticsReports`' own doc comment).
 */
class AnalyticsReportTest {
    @Test
    fun `an operator holding site configure reaches the site, conversion and tag-breakdown reports`() {
        val permissions = OperatorPermissions.Known(setOf(Permission.SITE_CONFIGURE))

        assertEquals(
            listOf(AnalyticsReport.Site, AnalyticsReport.Conversion, AnalyticsReport.TagBreakdown),
            visibleAnalyticsReports(permissions),
        )
    }

    @Test
    fun `an operator without site configure reaches nothing, so no overflow is drawn`() {
        val permissions = OperatorPermissions.Known(setOf(Permission.CONVERSATION_SEND, Permission.CUSTOMER_READ))

        assertTrue(visibleAnalyticsReports(permissions).isEmpty())
    }

    @Test
    fun `an operator holding nothing at all reaches nothing`() {
        assertTrue(visibleAnalyticsReports(OperatorPermissions.Known(emptySet())).isEmpty())
    }

    @Test
    fun `an unread permission set reaches nothing, the same fail-closed direction every other gate takes`() {
        assertTrue(visibleAnalyticsReports(OperatorPermissions.Unknown).isEmpty())
    }

    /** The menu's own founding rule (`26-58`, restated by [AnalyticsReport]'s doc comment): an entry
     * exists for a screen that exists. `26-73`/`26-74` each add one more; until then this enum holds
     * exactly these three members, and an accidental placeholder added here would show as a name with
     * nothing behind it — the inert control `26-15`/`26-40` refused twice. */
    @Test
    fun `only reports whose screen exists are named at all`() {
        assertEquals(
            listOf(AnalyticsReport.Site, AnalyticsReport.Conversion, AnalyticsReport.TagBreakdown),
            AnalyticsReport.entries.toList(),
        )
    }

    /** Every report is gated on a permission this app names rather than a string invented here —
     * `Permission`'s own vocabulary is copied verbatim from `ago-chat`, and a typo'd gate would hide a
     * screen from everybody with no failure anywhere else. */
    @Test
    fun `every report gates on a permission from the shared vocabulary`() {
        val known =
            setOf(
                Permission.CALENDAR_CONFIGURE,
                Permission.BOOKING_CONFIRM,
                Permission.BOOKING_REJECT,
                Permission.BOOKING_CANCEL,
                Permission.CUSTOMER_READ,
                Permission.SITE_MANAGE_OPERATORS,
                Permission.SITE_CONFIGURE,
                Permission.CONVERSATION_SEND,
            )

        assertTrue(AnalyticsReport.entries.all { it.permission in known })
    }
}
