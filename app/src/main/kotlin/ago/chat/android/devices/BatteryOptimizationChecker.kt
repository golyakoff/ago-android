package ago.chat.android.devices

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-128`: Settings → «Режим работы»'s own live read — behind a port for the identical reason
 * [NotificationPermissionChecker] is one (rule 2, read onto an Android client): a `ViewModel` calling
 * `PowerManager` directly would be untestable on a plain JVM.
 *
 * **`PowerManager.isIgnoringBatteryOptimizations(packageName)`, not the presence feature's own
 * [ago.chat.android.presence.BatteryOptimizationGate].** That interface answers a different question —
 * "should I still *ask* for the exemption" (folding in "already asked once" so `26-85`'s one-shot request
 * is never repeated) — where this one answers "what is the true system state right now", the same
 * live-truth contract [NotificationPermissionChecker]'s own doc comment states for
 * `NotificationManagerCompat.areNotificationsEnabled()`. Sharing one port between the two would make a
 * Settings row that only ever reads `true` after `26-85`'s own one-time request fires, regardless of what
 * the operator actually chose in the system dialog that opened.
 *
 * **Read fresh on every call, never cached** — the identical reason [NotificationPermissionChecker]'s own
 * doc comment gives: an operator can flip this from system Settings while this app is backgrounded.
 */
public interface BatteryOptimizationChecker {
    public fun isIgnoringBatteryOptimizations(): Boolean
}

@Singleton
public class AndroidBatteryOptimizationChecker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : BatteryOptimizationChecker {
        override fun isIgnoringBatteryOptimizations(): Boolean =
            // `getSystemService` answering `null` is not a documented real-world case for `PowerManager` -
            // treated as "already unrestricted" (the green state, nothing to recommend) rather than
            // "unknown", the identical fail-quiet convention `AndroidBatteryOptimizationGate` already
            // applies to the same possible-null read.
            context
                .getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName)
                ?: true
    }

/** `26-128`: turns [BatteryOptimizationChecker]'s own boolean into the shared [DeviceModeStatus] glyph -
 * `SettingsScreen`'s own `StatusGlyph` call site reads this rather than branching on the raw [Boolean]
 * twice (once for the glyph, once for the row's own title), and a plain JUnit test can drive it directly
 * with no `PowerManager`/Hilt/Compose in play at all. */
internal fun batteryModeStatus(unrestricted: Boolean): DeviceModeStatus =
    if (unrestricted) DeviceModeStatus.Ok else DeviceModeStatus.NeedsAttention
