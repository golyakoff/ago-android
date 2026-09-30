package ago.chat.android.devices

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-328`: [autostartLaunchAttempts] is the pure half of [openAutostartSettings] - the ordered plan, as
 * data, with no `Intent`/`Context` involved. The intent-launch try/catch [openAutostartSettings] itself
 * performs stays untested here by design (that function's own doc comment already treats a missing/renamed
 * OEM screen as expected, best-effort behaviour a real device is needed to observe) - what a plain JUnit
 * test *can* prove, and what actually matters for this item, is that the resolver tries every known Huawei
 * component before falling back, in the right order, and that the other OEMs' single-candidate shape is
 * unchanged.
 */
class AutostartLaunchAttemptsTest {
    @Test
    fun `none target has no launch attempts`() {
        assertEquals(emptyList<AutostartLaunchAttempt>(), autostartLaunchAttempts(AutostartSettingsTarget.None))
    }

    @Test
    fun `a single OEM component (xiaomi, oppo, realme, vivo) has exactly one attempt and no fallback`() {
        val xiaomi = autostartTargetFor("xiaomi")
        check(xiaomi is AutostartSettingsTarget.OemComponent)
        assertEquals(
            listOf(AutostartLaunchAttempt.Component(xiaomi)),
            autostartLaunchAttempts(xiaomi),
        )
    }

    /**
     * The exact shape this item fixes: Huawei's chain tries every known component, in the order
     * [autostartTargetFor] lists them, then the Phone Manager app's own launcher intent, then App-info -
     * never the other way around, since a less-certain fallback tried first would pre-empt a more specific
     * screen that might otherwise have resolved.
     */
    @Test
    fun `huawei chain tries every known component, then phone manager, then app-info`() {
        val huawei = autostartTargetFor("huawei")
        check(huawei is AutostartSettingsTarget.OemComponentChain)

        val attempts = autostartLaunchAttempts(huawei)

        assertEquals(
            listOf(
                AutostartLaunchAttempt.Component(huawei.candidates[0]),
                AutostartLaunchAttempt.Component(huawei.candidates[1]),
                AutostartLaunchAttempt.Component(huawei.candidates[2]),
                AutostartLaunchAttempt.Package("com.huawei.systemmanager"),
                AutostartLaunchAttempt.AppInfo,
            ),
            attempts,
        )
    }

    /** A chain with no [AutostartSettingsTarget.OemComponentChain.fallbackPackage] still ends in App-info -
     * the one attempt guaranteed to resolve is never itself conditional on a fallback package existing. */
    @Test
    fun `a chain with no fallback package still ends in app-info`() {
        val chainWithoutFallback =
            AutostartSettingsTarget.OemComponentChain(
                candidates = listOf(AutostartSettingsTarget.OemComponent("com.example.oem", "com.example.oem.Autostart")),
                fallbackPackage = null,
            )

        assertEquals(
            listOf(
                AutostartLaunchAttempt.Component(chainWithoutFallback.candidates[0]),
                AutostartLaunchAttempt.AppInfo,
            ),
            autostartLaunchAttempts(chainWithoutFallback),
        )
    }
}
