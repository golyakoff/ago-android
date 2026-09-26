package ago.chat.android.devices

import ago.chat.android.core.domain.devices.DeviceIdProvider
import ago.chat.android.core.domain.devices.InstallationIdProvider
import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-122`: [DeviceIdProvider] over `Settings.Secure.ANDROID_ID` - see that interface's own doc comment
 * for the full trade-off against a second stored UUID. `@ApplicationContext`'s `contentResolver`, the
 * identical accessor [AndroidNotificationPermissionChecker] and every other framework-reading class in
 * this package already goes through rather than a bare `Context` typed out here.
 *
 * **Falls back to [installationId] only for the narrow invalid-value case**, never as a second primary
 * path: a `null`/blank read (rare, some emulators and locked-down profiles) or the long-documented
 * all-devices sentinel `9774d56d682e549c` a small population of real Android 2.2 devices were once
 * known to report verbatim - both mean "this platform value cannot be trusted as a device identity", not
 * "this platform value is merely absent". [installationId] degrades gracefully in that case exactly the
 * way [DeviceIdProvider]'s own doc comment describes: same-install-only dedup, with `26-123`'s
 * time-based prune as the backstop.
 *
 * **Namespaced with an `"android:"` prefix**, not the bare platform value: [installationId]'s own
 * stored UUID and a raw `ANDROID_ID` hex string happen to differ in shape today, but nothing enforces
 * that forever, and a future value from either source colliding with the other by coincidence would
 * silently merge two different devices' rows. The prefix costs one string concatenation and removes the
 * possibility entirely.
 */
@Singleton
public class AndroidIdDeviceIdProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val installationId: InstallationIdProvider,
    ) : DeviceIdProvider {
        // `HardwareIds`: lint's own generic warning against reading a device identifier - this class
        // exists for exactly that read, for the reason [DeviceIdProvider]'s own doc comment states in
        // full (a value that survives a reinstall, which nothing else on this platform offers), not an
        // oversight lint caught.
        @SuppressLint("HardwareIds")
        override suspend fun deviceId(): String {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            return if (androidId.isNullOrBlank() || androidId == KNOWN_INVALID_ANDROID_ID) {
                installationId.installationId()
            } else {
                "android:$androidId"
            }
        }

        private companion object {
            // The sentinel a population of real (pre-8.0) Android devices were documented to report
            // verbatim for ANDROID_ID, rather than a genuinely unique value - never a real device's own id.
            const val KNOWN_INVALID_ANDROID_ID = "9774d56d682e549c"
        }
    }
