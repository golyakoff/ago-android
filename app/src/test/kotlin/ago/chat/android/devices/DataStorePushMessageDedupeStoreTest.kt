package ago.chat.android.devices

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `26-18`: real, on-disk proof that a seen key persists - [DataStoreThemePreferencesTest]'s own shape and
 * its own documented restraint, deliberately followed rather than the more thorough-looking alternative:
 * **at most one real write against any one on-disk file within a single test.** That class's own doc
 * comment on [withStoreAt]'s twin (`withDataStoreAt`) records a real, reproducible finding on this
 * platform - a second real write against a file a `DataStore` instance already touched can throw
 * `IOException: ...multiple instances of DataStore...` even after the earlier instance's own scope was
 * cancelled and joined. This suite was where that finding repeated for a *single* instance issuing two
 * sequential real writes (two genuinely new keys, one after another) - confirmed live, deterministically,
 * not merely suspected. The fix is the same one that class already reaches for: cover the *multi-key,
 * multi-write* behaviour with [SeenKeysWindowTest] instead, which needs no file at all, and keep this
 * suite to the one real-write shape that is safe: a single write, read back from an independently rebuilt
 * store. A same-instance *duplicate* check is still exercised here (`a duplicate reported back
 * immediately, no rebuild needed`), because [DataStorePushMessageDedupeStore.markSeenIfNew] only ever
 * writes when [SeenKeysWindow.apply] reports a real change - a duplicate's own `edit` call never reaches
 * the file at all, so it does not reproduce the two-real-writes finding above.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStorePushMessageDedupeStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `the first time a key is seen, it is reported new`() =
        runTest {
            withStoreAt(file()) { store ->
                assertTrue(store.markSeenIfNew("msg-1"))
            }
        }

    @Test
    fun `a duplicate reported back immediately, no rebuild needed - its own edit call never writes`() =
        runTest {
            withStoreAt(file()) { store ->
                assertTrue(store.markSeenIfNew("msg-1"))
                assertFalse(store.markSeenIfNew("msg-1"))
            }
        }

    @Test
    fun `a seen key survives a fresh read from an independently rebuilt store - the offline-burst case`() =
        runTest {
            val file = file()

            withStoreAt(file) { it.markSeenIfNew("msg-1") }

            // A brand new `DataStore`/scope over the identical file - simulating the `Service` process
            // being torn down and recreated between deliveries, which `PushMessageDedupeStore`'s own
            // doc comment names as exactly the reason an in-memory set would not be enough here. Reading
            // this back is itself a duplicate check, so it never issues a second real write either.
            withStoreAt(file) { afterRestart ->
                assertFalse(afterRestart.markSeenIfNew("msg-1"))
            }
        }

    private fun file(name: String = "device"): File = File(tempFolder.root, "$name.preferences_pb")

    /** [DataStoreThemePreferencesTest.withDataStoreAt]'s own shape and its own doc comment on what the
     * retry is - and is not - a fix for. */
    private suspend fun <T> withStoreAt(
        file: File,
        block: suspend (DataStorePushMessageDedupeStore) -> T,
    ): T {
        var attempt = 0
        while (true) {
            attempt++
            val job = Job()
            val scope = CoroutineScope(Dispatchers.IO + job)
            val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            try {
                val result = block(DataStorePushMessageDedupeStore(store))
                job.cancelAndJoin()
                return result
            } catch (failure: java.io.IOException) {
                job.cancelAndJoin()
                if (attempt > 1 || failure.message?.contains("multiple instances of DataStore") != true) throw failure
                delay(200)
            }
        }
    }
}
