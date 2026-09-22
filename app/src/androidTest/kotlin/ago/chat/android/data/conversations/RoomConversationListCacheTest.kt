package ago.chat.android.data.conversations

import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.ConversationSummary
import ago.chat.android.data.AgoChatDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-14`: Room's own real behaviour — insert, query, and the one distinction
 * [ago.chat.android.core.domain.conversations.ConversationListCache.read]'s own doc comment insists on
 * ("never written" vs "written, and genuinely empty"). Needs no network and no operator session, which
 * is exactly why this worktree could build and run it without the real backend `26-11`/`26-12`/`26-13`
 * already named as out of reach here — `Room.inMemoryDatabaseBuilder` is real SQLite, running on the
 * `ago-test` emulator, with nothing else involved.
 */
@RunWith(AndroidJUnit4::class)
class RoomConversationListCacheTest {
    private lateinit var database: AgoChatDatabase
    private lateinit var cache: RoomConversationListCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AgoChatDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        cache = RoomConversationListCache(database.conversationRowDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun neverWrittenReadsAsNullNotAsAnEmptyQueue() =
        runTest {
            assertNull(cache.read())
        }

    @Test
    fun aWrittenQueueRoundTripsBothBucketsInFull() =
        runTest {
            val queue =
                ConversationQueue(
                    waiting = listOf(summary("w1"), summary("w2")),
                    assignedToMe = listOf(summary("a1")),
                )

            cache.write(queue)
            val read = cache.read()

            assertEquals(queue, read)
        }

    @Test
    fun aGenuinelyEmptyQueueReadsAsEmptyListsNotAsNull() =
        runTest {
            cache.write(ConversationQueue(waiting = emptyList(), assignedToMe = emptyList()))

            val read = cache.read()

            assertEquals(ConversationQueue(emptyList(), emptyList()), read)
        }

    @Test
    fun aSecondWriteFullyReplacesTheFirst_aConversationClaimedOutOfWaitingDoesNotLingerThere() =
        runTest {
            cache.write(ConversationQueue(waiting = listOf(summary("c1")), assignedToMe = emptyList()))

            // The claim moved it - the second write is this screen's whole new answer, not a merge.
            cache.write(ConversationQueue(waiting = emptyList(), assignedToMe = listOf(summary("c1"))))

            val read = cache.read()
            assertEquals(emptyList<ConversationSummary>(), read?.waiting)
            assertEquals(listOf("c1"), read?.assignedToMe?.map { it.conversationId })
        }

    private fun summary(id: String) =
        ConversationSummary(
            conversationId = id,
            visitorId = "visitor-$id",
            emojiCreature = "🦊",
            emojiFood = "🍕",
            visitorName = null,
            createdAt = "2026-09-22T09:00:00Z",
            operatorUnreadCount = 0,
        )
}
