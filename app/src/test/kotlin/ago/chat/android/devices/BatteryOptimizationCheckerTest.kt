package ago.chat.android.devices

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-128`: [batteryModeStatus] proven directly — the one place `SettingsScreen`'s green-check/orange-
 * exclamation choice for «Режим работы» is actually decided, kept as a plain function precisely so this
 * test needs no `PowerManager`, no Hilt component, and no Compose test rule to prove it.
 */
class BatteryOptimizationCheckerTest {
    @Test
    fun `unrestricted maps to Ok, the green check state`() {
        assertEquals(DeviceModeStatus.Ok, batteryModeStatus(unrestricted = true))
    }

    @Test
    fun `restricted maps to NeedsAttention, the orange exclamation state`() {
        assertEquals(DeviceModeStatus.NeedsAttention, batteryModeStatus(unrestricted = false))
    }
}
