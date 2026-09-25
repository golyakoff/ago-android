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
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            autostartTargetFor("HUAWEI"),
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
}
