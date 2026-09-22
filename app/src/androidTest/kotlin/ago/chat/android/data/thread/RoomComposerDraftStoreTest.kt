package ago.chat.android.data.thread

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
 * `26-15`: real SQLite, the same `RoomConversationListCacheTest` shape - proves the one property this
 * item's own Done-when needs: a draft written for a conversation reads back exactly, a cleared
 * conversation reads back as no draft at all (never an empty string standing in for "nothing saved"),
 * and two conversations' own drafts never collide.
 */
@RunWith(AndroidJUnit4::class)
class RoomComposerDraftStoreTest {
    private lateinit var database: AgoChatDatabase
    private lateinit var store: RoomComposerDraftStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, AgoChatDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        store = RoomComposerDraftStore(database.composerDraftDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun neverSavedReadsAsNull() =
        runTest {
            assertNull(store.read("c1"))
        }

    @Test
    fun aSavedDraftRoundTripsExactly() =
        runTest {
            store.write("c1", "Здравствуйте, чем могу помочь?")

            assertEquals("Здравствуйте, чем могу помочь?", store.read("c1"))
        }

    @Test
    fun overwritingReplacesRatherThanAppending() =
        runTest {
            store.write("c1", "первая версия")
            store.write("c1", "вторая версия")

            assertEquals("вторая версия", store.read("c1"))
        }

    @Test
    fun clearingLeavesNoDraftAtAll_notAnEmptyString() =
        runTest {
            store.write("c1", "черновик")

            store.clear("c1")

            assertNull(store.read("c1"))
        }

    @Test
    fun twoConversationsOwnDraftsNeverCollide() =
        runTest {
            store.write("c1", "ответ для первого диалога")
            store.write("c2", "ответ для второго диалога")

            store.clear("c1")

            assertNull(store.read("c1"))
            assertEquals("ответ для второго диалога", store.read("c2"))
        }

    /**
     * `26-15`'s own Done-when: "including after the process is killed and restored". A real process
     * death is not reachable from a JVM/instrumented test either, but what makes the guarantee real is
     * that the draft is a committed SQLite row, not anything held in memory - closing this exact
     * `AgoChatDatabase` instance and opening a fresh one against the same file is the honest simulation
     * of that boundary, the same one `RoomConversationListCacheTest`'s own `tearDown`/`setUp` pair
     * relies on implicitly for the queue cache.
     */
    @Test
    fun survivesReopeningTheDatabaseFromScratch() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val fileBackedFirst =
                Room
                    .databaseBuilder(context, AgoChatDatabase::class.java, "composer-draft-test.db")
                    .allowMainThreadQueries()
                    .build()
            try {
                RoomComposerDraftStore(fileBackedFirst.composerDraftDao()).write("c1", "переживший смерть процесса")
            } finally {
                fileBackedFirst.close()
            }

            val reopened =
                Room
                    .databaseBuilder(context, AgoChatDatabase::class.java, "composer-draft-test.db")
                    .allowMainThreadQueries()
                    .build()
            try {
                assertEquals("переживший смерть процесса", RoomComposerDraftStore(reopened.composerDraftDao()).read("c1"))
            } finally {
                reopened.close()
                context.deleteDatabase("composer-draft-test.db")
            }
        }
}
