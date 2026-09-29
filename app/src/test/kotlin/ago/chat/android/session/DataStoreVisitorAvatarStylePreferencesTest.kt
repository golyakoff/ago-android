package ago.chat.android.session

import ago.chat.android.ui.components.VisitorAvatarStyle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `26-285`'s own real, on-disk proof - [DataStoreThemePreferencesTest]'s own shape, restated for
 * [DataStoreVisitorAvatarStylePreferences] over a real file rather than a fake, for the identical
 * reason that suite's own doc comment states (`PreferenceDataStoreFactory.create` needs nothing but a
 * [File], no `Context`, no Robolectric).
 *
 * See that suite's own doc comment for why every `DataStore` here gets its own [CoroutineScope],
 * cancelled and *joined*, before a second one ever points at the same file - the identical
 * `androidx.datastore.core.FileStorage` contract, not test plumbing to work around.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreVisitorAvatarStylePreferencesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `with nothing ever written, the default is Emoji`() =
        runTest {
            withDataStoreAt(file()) { preferences ->
                assertEquals(VisitorAvatarStyle.Emoji, preferences.style.first())
            }
        }

    @Test
    fun `a chosen style survives a fresh read from an independently rebuilt store`() =
        runTest {
            val file = file()

            withDataStoreAt(file) { beforeRestart -> beforeRestart.setStyle(VisitorAvatarStyle.Initials) }

            // A brand new `DataStore`/scope over the identical file, sharing nothing in memory with
            // `beforeRestart` above - the only way to simulate "the process died and started again"
            // without an emulator.
            withDataStoreAt(file) { afterRestart ->
                assertEquals(VisitorAvatarStyle.Initials, afterRestart.style.first())
            }
        }

    @Test
    fun `setStyle is what changes the value the next read sees - not merely the next instance`() =
        runTest {
            withDataStoreAt(file("a")) { preferences ->
                assertEquals(VisitorAvatarStyle.Emoji, preferences.style.first())

                preferences.setStyle(VisitorAvatarStyle.Initials)
                assertEquals(VisitorAvatarStyle.Initials, preferences.style.first())
            }

            withDataStoreAt(file("b")) { preferences ->
                assertEquals(VisitorAvatarStyle.Emoji, preferences.style.first())
            }
        }

    private fun file(name: String = "visitor_avatar_style"): File = File(tempFolder.root, "$name.preferences_pb")

    /** [DataStoreThemePreferencesTest.withDataStoreAt]'s own shape and own reasoning, restated for
     * [DataStoreVisitorAvatarStylePreferences]. */
    private suspend fun withDataStoreAt(
        file: File,
        block: suspend (DataStoreVisitorAvatarStylePreferences) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            attempt++
            val job = Job()
            val scope = CoroutineScope(Dispatchers.IO + job)
            val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            try {
                block(DataStoreVisitorAvatarStylePreferences(store))
                job.cancelAndJoin()
                return
            } catch (failure: java.io.IOException) {
                job.cancelAndJoin()
                if (attempt > 1 || failure.message?.contains("multiple instances of DataStore") != true) throw failure
                delay(200)
            }
        }
    }
}
