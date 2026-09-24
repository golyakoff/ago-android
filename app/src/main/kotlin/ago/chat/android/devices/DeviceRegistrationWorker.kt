package ago.chat.android.devices

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * `26-06`: the third of the three call sites - `docs/architecture/push-notifications.md`'s own reason
 * this exists at all: `onNewToken` cannot fire for an app that was not running when a rotation
 * happened, so this is the app's own backstop, on the interval and constraint
 * [WorkManagerDeviceRegistrationScheduler] states and justifies.
 *
 * **Deliberately thin - no logic of its own to test.** Everything this class could get wrong
 * ("does re-registering work", "does it read the right installation id") is
 * [DeviceRegistrationCoordinator.registerThisDevice]'s own, already covered by
 * `DeviceRegistrationCoordinatorTest` on a plain JVM with no `Context` at all. What is left here -
 * "does `doWork()` map a successful write to `Result.success()` and a failed one to `Result.retry()`"
 * - is exactly the one thing that *does* need a real `Context`/`WorkerParameters` pair to construct at
 * all, which is `DeviceRegistrationWorkerTest`'s own job as an instrumented test
 * (`androidx.work:work-testing`'s `TestListenableWorkerBuilder`), the identical "the unit is a plain
 * JVM test, the one thing that needs a real Android runtime is instrumented" split
 * `RoomConversationListCacheTest` already draws for Room's own persistence.
 *
 * **`EntryPointAccessors`, not a `@HiltWorker`/`HiltWorkerFactory`.** Hilt's own `androidx.hilt:hilt-work`
 * is the documented path for an app with several injected workers, and it costs a `Configuration.Provider`
 * on `AgoChatApplication`, disabling `WorkManagerInitializer` in the manifest, and a second KSP
 * annotation-processing artifact. With exactly one dependency to fetch and exactly one worker in this
 * app, `EntryPointAccessors.fromApplication` is the officially documented lighter alternative
 * (Hilt's own "Inject WorkManager objects with a custom entry point" guide) - it reads a
 * `@Singleton` straight out of the graph Hilt already built at `Application.onCreate()`, at no cost to
 * `AgoChatApplication`'s own responsibilities, and without widening the DI surface for a call site
 * this app has exactly one of. If a second worker arrives, that is the point to revisit this choice,
 * not before.
 */
public class DeviceRegistrationWorker(
    context: Context,
    params: WorkerParameters,
    // `26-06`: the one seam `DeviceRegistrationWorkerTest` needs - `null` in every real path (the
    // 2-arg constructor `WorkManager` itself calls reflectively can never supply a third argument), a
    // fake in the test's own hand-rolled `WorkerFactory`. Without this, proving "does `doWork()` map a
    // successful write to `Result.success()`" would mean running this test against the app's real Hilt
    // graph - a real network call and a real RuStore SDK call this test has no business making.
    private val deviceRegistrarOverride: DeviceRegistrar? = null,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val coordinator =
            deviceRegistrarOverride
                ?: EntryPointAccessors
                    .fromApplication(applicationContext, DeviceRegistrationWorkerEntryPoint::class.java)
                    .deviceRegistrationCoordinator()

        return if (coordinator.registerThisDevice()) Result.success() else Result.retry()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
public interface DeviceRegistrationWorkerEntryPoint {
    public fun deviceRegistrationCoordinator(): DeviceRegistrationCoordinator
}
