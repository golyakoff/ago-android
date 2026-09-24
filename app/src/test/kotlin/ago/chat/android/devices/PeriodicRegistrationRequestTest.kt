package ago.chat.android.devices

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * `26-06`: a narrow, plain-JVM-testable assertion on the shape of the `PeriodicWorkRequest`
 * [WorkManagerDeviceRegistrationScheduler] builds - interval and constraints only, never enqueueing it
 * and never driving simulated time through it. `docs/backlog/26-06-*.md`'s own two-mistakes warning:
 * a previous worker this session hit an infinite loop trying to `advanceUntilIdle()` a periodic
 * coroutine into simulating several real periods. This test exists precisely so nobody has to - the
 * `WorkRequest`'s own `workSpec` already states its interval and constraints without WorkManager ever
 * running one tick of it.
 */
class PeriodicRegistrationRequestTest {
    @Test
    fun `the interval is exactly 24 hours - not the 15-minute floor PeriodicWorkRequest itself allows`() {
        val request =
            PeriodicWorkRequestBuilder<DeviceRegistrationWorker>(
                WorkManagerDeviceRegistrationScheduler.PERIODIC_INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).build()

        assertEquals(TimeUnit.HOURS.toMillis(24), request.workSpec.intervalDuration)
    }

    @Test
    fun `the job requires a connected network`() {
        val request =
            PeriodicWorkRequestBuilder<DeviceRegistrationWorker>(
                WorkManagerDeviceRegistrationScheduler.PERIODIC_INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }
}
