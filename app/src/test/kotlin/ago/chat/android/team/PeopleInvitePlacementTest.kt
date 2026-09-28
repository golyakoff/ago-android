package ago.chat.android.team

import ago.chat.android.R
import ago.chat.android.core.domain.team.OperatorInviteEffectiveStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-263`: proves each of the five [OperatorInviteEffectiveStatus] values lands in the section the
 * approved design (`ago-android-design/team.html`) puts it, with the pill label that section shows — the
 * pure mapping [PeopleScreen]'s own sectioning reads from, exercised directly on a plain JVM (no
 * Robolectric, the same convention `VisitorEmojiPairNameTest` states). The label assertions compare
 * resource ids, not rendered words: which `R.string` each state resolves to is exactly what a mis-mapping
 * would get wrong, and the words themselves are proven by the string files in both locales.
 */
class PeopleInvitePlacementTest {
    @Test
    fun `Pending lands in the invites section`() {
        assertEquals(InvitePlacement.Pending, OperatorInviteEffectiveStatus.Pending.placement())
    }

    @Test
    fun `InTeam is shown through the roster, never in the invite list`() {
        assertEquals(InvitePlacement.InRoster, OperatorInviteEffectiveStatus.InTeam.placement())
    }

    @Test
    fun `Removed lands in the archive as the Removed kind`() {
        assertEquals(
            InvitePlacement.Archive(InviteArchiveKind.Removed),
            OperatorInviteEffectiveStatus.Removed.placement(),
        )
    }

    @Test
    fun `Revoked lands in the archive as the Revoked kind`() {
        assertEquals(
            InvitePlacement.Archive(InviteArchiveKind.Revoked),
            OperatorInviteEffectiveStatus.Revoked.placement(),
        )
    }

    @Test
    fun `Expired lands in the archive as the Expired kind`() {
        assertEquals(
            InvitePlacement.Archive(InviteArchiveKind.Expired),
            OperatorInviteEffectiveStatus.Expired.placement(),
        )
    }

    @Test
    fun `every effective status is placed - no state falls through`() {
        // Guards the exhaustiveness of `placement()` in one assertion: a new enum member added without a
        // placement would stop this compiling (the `when` is exhaustive) or leave a gap this catches.
        OperatorInviteEffectiveStatus.entries.forEach { status ->
            // Each must resolve to one of the three known placements; the individual tests above pin which.
            when (status.placement()) {
                is InvitePlacement.Pending, is InvitePlacement.InRoster, is InvitePlacement.Archive -> Unit
            }
        }
    }

    @Test
    fun `each archive kind carries its own neuter pill label`() {
        assertEquals(R.string.people_status_removed, InviteArchiveKind.Removed.pillLabelRes())
        assertEquals(R.string.people_invite_status_revoked, InviteArchiveKind.Revoked.pillLabelRes())
        assertEquals(R.string.people_invite_status_expired, InviteArchiveKind.Expired.pillLabelRes())
    }
}
