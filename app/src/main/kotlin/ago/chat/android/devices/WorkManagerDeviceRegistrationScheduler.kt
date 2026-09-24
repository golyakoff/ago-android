package ago.chat.android.devices

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-06`: [DeviceRegistrationScheduler] over the real `WorkManager` - the one class in this feature
 * that holds a `Context`, kept this small and this isolated precisely so nothing else in the feature
 * has to.
 *
 * **24 hours, with a `CONNECTED` constraint.** `docs/architecture/push-notifications.md`'s own
 * reasoning: `AgoPushMessagingService.onNewToken` already handles a rotation in real time while the app
 * is running - this job exists solely to catch one that happened while it was not. There is no
 * documented token lifetime to race, so nothing calls for the 15-minute floor `PeriodicWorkRequest`
 * itself enforces; a daily heartbeat is frequent enough that `last_seen_at` drifting more than a day
 * stale is a genuine "is this install still alive" signal, and infrequent enough to cost nothing worth
 * measuring in battery for a call this cheap. `NetworkType.CONNECTED` because a run with no network can
 * only ever fail the one thing it exists to do.
 *
 * **Scheduled once, on sign-in, and never explicitly cancelled on sign-out.** A signed-out install's
 * next tick calls [DeviceRegistrationCoordinator.registerThisDevice], which sends an unauthenticated
 * write the server answers `401`, which [DeviceRegistrationWorker] reads as `Result.retry()` - wasted
 * work, but never wrong output, and `ExistingPeriodicWorkPolicy.KEEP` already keeps a second sign-in
 * from enqueuing a second copy of the same job. Explicitly cancelling on every sign-out would need
 * `AgoAuthSession` to hold a second, `WorkManager`-shaped dependency for a property (bounded, harmless
 * retries against an idle endpoint) that costs nothing worth that coupling.
 */
@Singleton
public class WorkManagerDeviceRegistrationScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DeviceRegistrationScheduler {
        override fun schedulePeriodicRegistration() {
            val request =
                PeriodicWorkRequestBuilder<DeviceRegistrationWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        public companion object {
            public const val WORK_NAME: String = "ago-device-registration"
            public const val PERIODIC_INTERVAL_HOURS: Long = 24
        }
    }
