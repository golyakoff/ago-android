package ago.chat.android.conversations

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.Permission
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-90`'s own second Done-when box, asserted rather than eyeballed: the segmented control has
 * **three** segments for an operator holding `site:configure` and **two** for one who does not.
 *
 * A plain JVM test over [visibleConversationListTabs] rather than an instrumented Compose test,
 * because the rule this box is about is a rule, not a rendering - the identical split
 * `BottomDestinationTest` already draws for `visibleBottomDestinations`, which is the function this
 * one is modelled on. `ConversationListScreen` renders exactly the list it is handed
 * (`tabs.forEachIndexed`), so proving the list is proving the control.
 */
class ConversationListTabsTest {
    @Test
    fun `an operator holding site configure gets a third segment`() {
        val tabs = visibleConversationListTabs(OperatorPermissions.Known(setOf(Permission.SITE_CONFIGURE)))

        assertEquals(3, tabs.size)
        assertEquals(
            listOf(ConversationListTab.Mine, ConversationListTab.Waiting, ConversationListTab.All),
            tabs,
        )
    }

    /** `conversation:read` is deliberately **not** what unlocks this - every ordinary operator holds
     * it, and `GetAllConversationsForSiteHandler` gates the site-wide read on `site:configure`
     * precisely so that holding the ordinary one does not hand over everybody's conversations. An
     * operator with a realistic ordinary permission set must still see two segments. */
    @Test
    fun `an operator without site configure gets two segments, never a greyed-out third`() {
        val tabs =
            visibleConversationListTabs(
                OperatorPermissions.Known(setOf(Permission.CONVERSATION_SEND, Permission.CUSTOMER_READ)),
            )

        assertEquals(2, tabs.size)
        assertEquals(listOf(ConversationListTab.Mine, ConversationListTab.Waiting), tabs)
    }

    /** Hiding is the safe direction to guess wrong in - [OperatorPermissions.Unknown] is the state
     * before the one `GET /api/v1/operators/me` of the session has answered, and a tab drawn on a
     * guess would flicker away again. */
    @Test
    fun `permissions not yet read get two segments`() {
        assertEquals(2, visibleConversationListTabs(OperatorPermissions.Unknown).size)
    }

    /** `conversation:erase` is a second, independent gate and is deliberately not folded into the
     * first: it decides whether a row swipes, never whether the segment exists. A holder of it alone
     * (which `RegisterSiteHandler` never actually produces, but which nothing here may assume) must
     * not be handed the tab. */
    @Test
    fun `conversation erase alone does not open the tab`() {
        assertEquals(2, visibleConversationListTabs(OperatorPermissions.Known(setOf(Permission.CONVERSATION_ERASE))).size)
    }
}
