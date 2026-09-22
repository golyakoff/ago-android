package ago.chat.android.core.domain.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `26-16`: the one property this type exists for — [OperatorPermissions.Unknown] and a genuinely
 * permission-less [OperatorPermissions.Known] must answer [holds] identically (`false`), so that a
 * caller who only reads a `Boolean` cannot tell the two apart and is pushed toward the fail-closed
 * shape by construction, the same [ago.chat.android.core.domain.identity.ProbeOutcome]-style
 * three-state discipline this file's own doc comment cites.
 */
class OperatorPermissionsTest {
    @Test
    fun `unknown holds nothing`() {
        assertFalse(OperatorPermissions.Unknown.holds(Permission.CALENDAR_CONFIGURE))
    }

    @Test
    fun `known with an empty grant set holds nothing - the same answer as unknown`() {
        val known = OperatorPermissions.Known(emptySet())

        assertFalse(known.holds(Permission.CALENDAR_CONFIGURE))
        assertEquals(OperatorPermissions.Unknown.holds(Permission.CALENDAR_CONFIGURE), known.holds(Permission.CALENDAR_CONFIGURE))
    }

    @Test
    fun `known holds exactly what the server granted, nothing more`() {
        val known = OperatorPermissions.Known(setOf(Permission.CALENDAR_CONFIGURE))

        assertTrue(known.holds(Permission.CALENDAR_CONFIGURE))
        assertFalse(known.holds(Permission.SITE_CONFIGURE))
    }

    @Test
    fun `holdsAny is satisfied by exactly one of several names`() {
        val known = OperatorPermissions.Known(setOf(Permission.CUSTOMER_READ))

        assertTrue(known.holdsAny(listOf(Permission.CALENDAR_CONFIGURE, Permission.CUSTOMER_READ)))
    }

    @Test
    fun `holdsAny is false when the grant set matches none of the names asked for`() {
        val known = OperatorPermissions.Known(setOf(Permission.SITE_CONFIGURE))

        assertFalse(known.holdsAny(listOf(Permission.CALENDAR_CONFIGURE, Permission.CUSTOMER_READ)))
    }

    @Test
    fun `unknown never satisfies holdsAny, however many names are asked for`() {
        assertFalse(OperatorPermissions.Unknown.holdsAny(listOf(Permission.CALENDAR_CONFIGURE, Permission.CUSTOMER_READ)))
    }
}
