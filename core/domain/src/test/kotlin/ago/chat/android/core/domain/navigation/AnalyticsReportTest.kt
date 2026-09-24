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
    fun `an operator holding site configure reaches the site, conversion, tag-breakdown and booking-funnel reports, not phone reveals`() {
        val permissions = OperatorPermissions.Known(setOf(Permission.SITE_CONFIGURE))

        assertEquals(
            listOf(AnalyticsReport.Site, AnalyticsReport.Conversion, AnalyticsReport.TagBreakdown, AnalyticsReport.BookingFunnel),
            visibleAnalyticsReports(permissions),
        )
    }

    /** `docs/backlog/26-74-*.md`'s own Done-when: an operator holding only `calendar:configure` sees
     * «Показы телефонов» and none of the other four — the proof that this menu's gate is genuinely
     * per-entry rather than one shared switch, `AnalyticsReport`'s own doc comment on why [PhoneReveals]
     * carries its own field instead of reusing [Permission.SITE_CONFIGURE]. */
    @Test
    fun `an operator holding only calendar configure reaches phone reveals, and none of the other four`() {
        val permissions = OperatorPermissions.Known(setOf(Permission.CALENDAR_CONFIGURE))

        assertEquals(listOf(AnalyticsReport.PhoneReveals), visibleAnalyticsReports(permissions))
    }

    /** The reverse of the case above, restated so neither gate can be read as implying the other: holding
     * both permissions reaches every report there is, in this enum's own declaration order. */
    @Test
    fun `an operator holding both site configure and calendar configure reaches all five reports`() {
        val permissions = OperatorPermissions.Known(setOf(Permission.SITE_CONFIGURE, Permission.CALENDAR_CONFIGURE))

        assertEquals(AnalyticsReport.entries.toList(), visibleAnalyticsReports(permissions))
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
     * exists for a screen that exists. This enum now holds all five it will ever name, and an accidental
     * placeholder added here would show as a name with nothing behind it — the inert control
     * `26-15`/`26-40` refused twice. */
    @Test
    fun `only reports whose screen exists are named at all`() {
        assertEquals(
            listOf(
                AnalyticsReport.Site,
                AnalyticsReport.Conversion,
                AnalyticsReport.TagBreakdown,
                AnalyticsReport.BookingFunnel,
                AnalyticsReport.PhoneReveals,
            ),
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
