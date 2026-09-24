package ago.chat.android.data.conversations

import ago.chat.android.core.domain.conversations.ConversationQueue
import ago.chat.android.core.domain.conversations.ConversationSummary
import ago.chat.android.data.AgoChatDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
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

    // ------------------------------------------------------------- `26-46`: observeTotal (the badge)

    @Test
    fun observeTotalIsNullBeforeAnythingHasEverBeenWritten() =
        runTest {
            assertNull(cache.observeTotal().first())
        }

    @Test
    fun observeTotalSumsOnlyAssignedToMeUnreadCounts_waitingNeverContributes() =
        runTest {
            cache.write(
                ConversationQueue(
                    waiting = listOf(summary("w1", unread = 5)),
                    assignedToMe = listOf(summary("a1", unread = 2), summary("a2", unread = 3)),
                ),
            )

            assertEquals(5, cache.observeTotal().first())
        }

    @Test
    fun observeTotalIsARealZero_notReRenderedAsUnknown_onceEverythingIsRead() =
        runTest {
            cache.write(ConversationQueue(waiting = emptyList(), assignedToMe = listOf(summary("a1", unread = 0))))

            assertEquals(0, cache.observeTotal().first())
        }

    @Test
    fun observeTotalUpdatesLiveWhenTheCacheIsWrittenAgain_noRestartNeeded() =
        runTest {
            cache.write(ConversationQueue(waiting = emptyList(), assignedToMe = listOf(summary("a1", unread = 1))))
            assertEquals(1, cache.observeTotal().first())

            // `26-46`'s own Done-when: a hub push (`ConversationListViewModel.refresh` rewriting the
            // cache with a fresh queue) must bump this without anything re-subscribing - collecting the
            // identical `Flow` a second time after a second `write` is exactly that, since Room's own
            // `InvalidationTracker` is what re-runs the query, not a fresh subscription starting it.
            cache.write(ConversationQueue(waiting = emptyList(), assignedToMe = listOf(summary("a1", unread = 4))))
            assertEquals(4, cache.observeTotal().first())
        }

    private fun summary(
        id: String,
        unread: Int = 0,
    ) = ConversationSummary(
        conversationId = id,
        visitorId = "visitor-$id",
        emojiCreature = "🦊",
        emojiFood = "🍕",
        visitorName = null,
        createdAt = "2026-09-22T09:00:00Z",
        operatorUnreadCount = unread,
    )
}
