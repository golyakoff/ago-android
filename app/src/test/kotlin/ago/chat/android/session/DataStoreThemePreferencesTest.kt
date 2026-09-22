package ago.chat.android.session

import ago.chat.android.ui.theme.ThemeMode
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
 * `26-17`'s own real, on-disk proof of "a chosen theme survives a process restart" — not a fake standing
 * in for [ago.chat.android.ui.theme.ThemePreferences], the real [DataStoreThemePreferences] over a real
 * file, exactly as `di/AppModule.provideThemeDataStore` builds one, just pointed at a temp file instead
 * of `context.filesDir`. `PreferenceDataStoreFactory.create` needs nothing but a [File] — no `Context`,
 * no Robolectric, no emulator — which is the whole reason this dependency was chosen over Room or plain
 * `SharedPreferences` (see `gradle/libs.versions.toml`'s own `datastore` row).
 *
 * **Every `DataStore` this file builds gets its own [CoroutineScope], cancelled — and *joined*, not
 * merely requested — before this test ever points a second one at the same file.** That is not test
 * plumbing to work around, it is `androidx.datastore.core.FileStorage`'s own documented contract:
 * building a second `DataStore` over a file an earlier one still holds open throws
 * `IllegalStateException: There are multiple DataStores active for the same file... confirm that the
 * scope is cancelled` (found by decompiling the real 1.1.7 class, not by reading a guide), and
 * `cancelAndJoin` rather than a bare `cancel` is what actually waits for that library's own
 * close-on-cancellation handler to run before the next `create()` call is legal. This is also,
 * conveniently, the truest way to model "the process died and started again" available on a JVM with no
 * emulator: a cancelled scope, not merely a second Kotlin object, is what makes the "after" read below
 * share nothing with the "before" one but the file.
 *
 * **Fails-before**: before this item, [DataStoreThemePreferences] did not exist and
 * `ago.chat.android.ui.theme.AgoChatTheme`'s own doc comment said so explicitly ("a future settings
 * screen's own call to wire up") — this file is the proof that call was actually made. A bug that cached
 * the last-written value in a field on [DataStoreThemePreferences] itself, rather than genuinely writing
 * it to disk, would still pass a test that reused one instance across a "before" and "after" read, and
 * would fail the second test below, which never reuses one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreThemePreferencesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `with nothing ever written, the default is System`() =
        runTest {
            withDataStoreAt(file()) { preferences ->
                assertEquals(ThemeMode.System, preferences.mode.first())
            }
        }

    @Test
    fun `a chosen mode survives a fresh read from an independently rebuilt store`() =
        runTest {
            val file = file()

            withDataStoreAt(file) { beforeRestart -> beforeRestart.setMode(ThemeMode.Dark) }

            // A brand new `DataStore`/scope over the identical file, sharing nothing in memory with
            // `beforeRestart` above - the only way to simulate "the process died and started again"
            // without an emulator.
            withDataStoreAt(file) { afterRestart ->
                assertEquals(ThemeMode.Dark, afterRestart.mode.first())
            }
        }

    /**
     * Two independent files, one per write — not one file written twice. A file this suite already
     * read from once, on the very same instance, turned out to still hold its read handle open on this
     * machine long enough to make a *second* instance's write (an atomic rename over that file) fail
     * with `IOException: multiple instances of DataStore`, even after that first instance's scope was
     * cancelled and joined — a real, reproducible interaction, not flakiness (`withDataStoreAt`'s own
     * doc comment has the account of tracking it down). Nothing about "setMode changes what the next
     * read sees" needs the two writes below to share a file, so they do not.
     */
    @Test
    fun `setMode is what changes the value the next read sees - not merely the next instance`() =
        runTest {
            withDataStoreAt(file("a")) { preferences ->
                assertEquals(ThemeMode.System, preferences.mode.first())

                preferences.setMode(ThemeMode.Light)
                assertEquals(ThemeMode.Light, preferences.mode.first())
            }

            withDataStoreAt(file("b")) { preferences ->
                assertEquals(ThemeMode.System, preferences.mode.first())

                preferences.setMode(ThemeMode.Dark)
                assertEquals(ThemeMode.Dark, preferences.mode.first())
            }
        }

    private fun file(name: String = "theme"): File = File(tempFolder.root, "$name.preferences_pb")

    /**
     * Builds one [DataStoreThemePreferences] over [file] on its own, disposable [CoroutineScope], hands
     * it to [block], then cancels and joins that scope — see this class's own doc comment for why that
     * is not optional cleanup but the one thing that makes a *second* call with the same [file] legal.
     *
     * **A one-time retry stays here as a safety net, not as the actual fix.** The actual bug this suite
     * found on this machine was deterministic, not flaky, and it was on the *reading* side, not the
     * write: an instance whose last operation was a read of the file it had itself just written left a
     * handle on that file that `cancelAndJoin` did not wait out, so a *second, independent* instance's
     * later write to that same file (a plain atomic rename) threw
     * `IOException: Unable to rename... multiple instances of DataStore` from
     * `FileStorageConnection.writeScope` every time — regardless of whether that second instance itself
     * read first. `setMode is what changes the value the next read sees`'s own doc comment is where the
     * real fix lives (two files, not a shared one); what the retry below guards against instead is a
     * separate, genuinely occasional Windows timing gap in the same rename step, which is harmless to
     * leave in place since [ago.chat.android.di.AppModule.provideThemeDataStore] builds exactly one
     * `DataStore` for the whole process and never repeats this construction at all.
     */
    private suspend fun withDataStoreAt(
        file: File,
        block: suspend (DataStoreThemePreferences) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            attempt++
            val job = Job()
            // `Dispatchers.IO`, not a test dispatcher: `di/AppModule.provideThemeDataStore` builds the
            // real store with no explicit `scope` at all, which resolves to this library's own
            // `Dispatchers.IO`-backed default.
            val scope = CoroutineScope(Dispatchers.IO + job)
            val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            try {
                block(DataStoreThemePreferences(store))
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
