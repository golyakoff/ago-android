package ago.chat.android.devices

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `26-06`: real, on-disk proof that an installation id is generated once and read forever after - the
 * identical `DataStoreThemePreferencesTest` shape (a real temp file, no `Context`, no Robolectric), for
 * the property that item's own suite does not need to prove: **concurrent first callers must agree on
 * one id**, which is [DataStoreInstallationId]'s own doc comment's claim about generating the id inside
 * `DataStore.edit`'s transform rather than in a separate read-then-write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreInstallationIdTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `the first call generates a real, non-blank id`() =
        runTest {
            withInstallationIdAt(file()) { installationId ->
                val id = installationId.installationId()
                assertNotEquals("", id.trim())
            }
        }

    @Test
    fun `a later call on the same store returns the identical id, not a new one`() =
        runTest {
            withInstallationIdAt(file()) { installationId ->
                val first = installationId.installationId()
                val second = installationId.installationId()
                assertEquals(first, second)
            }
        }

    @Test
    fun `the id survives a fresh read from an independently rebuilt store`() =
        runTest {
            val file = file()

            val generated = withInstallationIdAt(file) { it.installationId() }

            // A brand new `DataStore`/scope over the identical file - the same "process died and
            // started again" simulation `DataStoreThemePreferencesTest` already establishes.
            withInstallationIdAt(file) { afterRestart ->
                assertEquals(generated, afterRestart.installationId())
            }
        }

    @Test
    fun `two concurrent first callers on the same store still agree on one id`() =
        runTest {
            withInstallationIdAt(file()) { installationId ->
                val results =
                    listOf(
                        async { installationId.installationId() },
                        async { installationId.installationId() },
                    ).awaitAll()

                assertEquals(results[0], results[1])
            }
        }

    private fun file(name: String = "device"): File = File(tempFolder.root, "$name.preferences_pb")

    /** [DataStoreThemePreferencesTest.withDataStoreAt]'s own shape and its own retry reasoning, applied
     * to [DataStoreInstallationId] instead of `DataStoreThemePreferences`. */
    private suspend fun <T> withInstallationIdAt(
        file: File,
        block: suspend (DataStoreInstallationId) -> T,
    ): T {
        var attempt = 0
        while (true) {
            attempt++
            val job = Job()
            val scope = CoroutineScope(Dispatchers.IO + job)
            val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            try {
                val result = block(DataStoreInstallationId(store))
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
