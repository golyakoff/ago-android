package ago.chat.android.devices

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `26-06`: proves the one thing [DeviceRegistrationWorker] itself owns - mapping
 * [DeviceRegistrar.registerThisDevice]'s `Boolean` to the `Result` `WorkManager` actually reads -
 * without ever driving simulated periods through it (`docs/backlog/26-06-*.md`'s own warning against
 * that trap) and without touching this app's real Hilt graph, real network, or the real RuStore SDK
 * (a hand-rolled [WorkerFactory] below, not [DeviceRegistrationWorker]'s own `EntryPointAccessors`
 * path - that class's own `deviceRegistrarOverride` constructor parameter exists for exactly this).
 *
 * Instrumented rather than a plain JVM test because [TestListenableWorkerBuilder] itself needs a real
 * `Context`/`WorkerParameters` pair to build a real `CoroutineWorker` at all - the identical
 * `RoomConversationListCacheTest` reasoning for why persistence needing a real Android runtime gets an
 * instrumented test rather than a plain one.
 */
@RunWith(AndroidJUnit4::class)
class DeviceRegistrationWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // `26-06`: camelCase, not this project's usual backtick-quoted sentence - an androidTest method
    // name becomes part of a DEX'd class name for its own suspend-lambda continuation, and R8 refuses a
    // space there outright ("Space characters in SimpleName ... are not allowed prior to DEX version
    // 040"), confirmed the hard way against this exact file. Every existing androidTest in this
    // codebase (`RoomConversationListCacheTest`, `RoomComposerDraftStoreTest`) already uses camelCase
    // for the identical reason; only the plain JVM `test` source set - never DEX'd - can afford a
    // backtick name.
    @Test
    fun aSuccessfulRegistrationIsResultSuccess() =
        runTest {
            val worker = workerWith(FakeDeviceRegistrar(result = true))

            val result = worker.doWork()

            assertEquals(Result.success(), result)
        }

    @Test
    fun aFailedRegistrationIsResultRetryNotASilentSuccess() =
        runTest {
            val worker = workerWith(FakeDeviceRegistrar(result = false))

            val result = worker.doWork()

            assertEquals(Result.retry(), result)
        }

    private fun workerWith(deviceRegistrar: FakeDeviceRegistrar): DeviceRegistrationWorker =
        TestListenableWorkerBuilder<DeviceRegistrationWorker>(context)
            .setWorkerFactory(FakeWorkerFactory(deviceRegistrar))
            .build()

    private class FakeDeviceRegistrar(
        private val result: Boolean,
    ) : DeviceRegistrar {
        override val pushAvailability = MutableStateFlow<PushAvailability?>(null)

        override suspend fun registerThisDevice(): Boolean = result
    }

    /**
     * A **named** class, not an anonymous `object : WorkerFactory() { ... }` literal - the anonymous
     * form, tried first, synthesised a DEX class name that embedded the enclosing backtick-quoted test
     * method's own name (spaces included), which R8 refuses outright: "Space characters in SimpleName
     * ... are not allowed prior to DEX version 040". Every test name in this file (and across this
     * project's whole test suite) is a backtick-quoted sentence for readability; a named top-level class
     * sidesteps the naming rule entirely rather than asking every future androidTest in this codebase to
     * avoid anonymous classes inside descriptively-named test methods.
     */
    private class FakeWorkerFactory(
        private val deviceRegistrar: DeviceRegistrar,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = DeviceRegistrationWorker(appContext, workerParameters, deviceRegistrar)
    }
}
