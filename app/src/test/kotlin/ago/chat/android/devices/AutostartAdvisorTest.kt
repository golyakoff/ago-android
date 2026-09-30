package ago.chat.android.devices

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-128`: [autostartRecommendationFor]/[autostartTargetFor] proven as plain functions over an explicit
 * manufacturer string — never through [android.os.Build.MANUFACTURER] itself, which no plain JUnit test
 * can set. [ManufacturerAutostartAdvisor] is the one-line wrapper that supplies the live value; nothing
 * about the actual decision needs a `Robolectric`/instrumented test to prove.
 */
class AutostartAdvisorTest {
    @Test
    fun `a manufacturer known to restrict autostart needs attention`() {
        for (manufacturer in listOf("Xiaomi", "HUAWEI", "oppo", "vivo", "realme")) {
            assertEquals(
                "$manufacturer should need attention",
                DeviceModeStatus.NeedsAttention,
                autostartRecommendationFor(manufacturer),
            )
        }
    }

    @Test
    fun `samsung and an unrecognised manufacturer are treated as fine`() {
        assertEquals(DeviceModeStatus.Ok, autostartRecommendationFor("samsung"))
        assertEquals(DeviceModeStatus.Ok, autostartRecommendationFor("Samsung"))
        assertEquals(DeviceModeStatus.Ok, autostartRecommendationFor("Google"))
        assertEquals(DeviceModeStatus.Ok, autostartRecommendationFor(""))
    }

    @Test
    fun `manufacturer matching is case-insensitive`() {
        assertEquals(autostartRecommendationFor("xiaomi"), autostartRecommendationFor("XIAOMI"))
        assertEquals(autostartTargetFor("xiaomi"), autostartTargetFor("XIAOMI"))
    }

    @Test
    fun `each known-restrictive manufacturer resolves to its own OEM component`() {
        assertEquals(
            AutostartSettingsTarget.OemComponent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            autostartTargetFor("Xiaomi"),
        )
        assertEquals(
            AutostartSettingsTarget.OemComponent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            autostartTargetFor("OPPO"),
        )
        assertEquals(
            AutostartSettingsTarget.OemComponent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            autostartTargetFor("realme"),
        )
        assertEquals(
            AutostartSettingsTarget.OemComponent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
            autostartTargetFor("vivo"),
        )
    }

    @Test
    fun `samsung and an unrecognised manufacturer have no known target`() {
        assertEquals(AutostartSettingsTarget.None, autostartTargetFor("samsung"))
        assertEquals(AutostartSettingsTarget.None, autostartTargetFor("Google"))
    }

    /**
     * `26-328`: Huawei alone resolves to [AutostartSettingsTarget.OemComponentChain] rather than a single
     * [AutostartSettingsTarget.OemComponent] - the real-device failure this item fixes was exactly a single
     * component name that stopped resolving. Asserts the ordered candidate list (most-recently-observed
     * component first) and the Phone Manager fallback package, both of which [autostartLaunchAttempts]
     * relies on to build its own ordered plan.
     */
    @Test
    fun `huawei resolves to an ordered component chain with a phone-manager fallback`() {
        val target = autostartTargetFor("HUAWEI")
        check(target is AutostartSettingsTarget.OemComponentChain)
        assertEquals(
            listOf(
                AutostartSettingsTarget.OemComponent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                AutostartSettingsTarget.OemComponent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
                AutostartSettingsTarget.OemComponent(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                ),
            ),
            target.candidates,
        )
        assertEquals("com.huawei.systemmanager", target.fallbackPackage)
    }

    /**
     * `26-328`: case-insensitivity ("HUAWEI"/"huawei"/"Huawei") must hold for the chain the same way
     * [`manufacturer matching is case-insensitive`] already proves it for a plain [AutostartSettingsTarget
     * .OemComponent] - a data class's own `equals` makes this a direct comparison, no special-casing needed.
     */
    @Test
    fun `huawei chain matching is case-insensitive`() {
        assertEquals(autostartTargetFor("huawei"), autostartTargetFor("HUAWEI"))
        assertEquals(autostartTargetFor("Huawei"), autostartTargetFor("HUAWEI"))
    }
}
