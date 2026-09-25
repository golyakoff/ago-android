package ago.chat.android.devices

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-128`: the left status glyph shared by both new Settings → «Уведомления» rows («Режим работы» and
 * «Автозапуск») — one green [Ok] state (Material `check`, `AgoIcons.Check`) and one orange
 * [NeedsAttention] state (Material `exclamation`, `AgoIcons.Exclamation`), never a third. Battery mode
 * derives this from [BatteryOptimizationChecker]'s real system read; autostart derives it from
 * [AutostartAdvisor] below, which is a guess, never a read — see that interface's own doc comment for why
 * a single shared enum still names both the same way rather than inventing a "confirmed vs. guessed"
 * distinction the UI would then have to draw twice.
 */
public enum class DeviceModeStatus {
    Ok,
    NeedsAttention,
}

/**
 * Where the «Настройки автозапуска» button leads. [OemComponent] names a package/class pair this app has
 * a reverse-engineered mapping for; [None] means no such mapping exists for this manufacturer (Samsung, an
 * unrecognised OEM, or plain AOSP) — [SettingsScreen][ago.chat.android.shell.SettingsScreen] renders that
 * as plain explanatory text instead of a dead button, never as a button that opens nothing.
 */
public sealed interface AutostartSettingsTarget {
    public data class OemComponent(
        public val packageName: String,
        public val className: String,
    ) : AutostartSettingsTarget

    public data object None : AutostartSettingsTarget
}

/**
 * `26-128`'s own hardest constraint: **Android has no AOSP API to read whether autostart-after-reboot is
 * granted.** `PowerManager.isIgnoringBatteryOptimizations` is a real, documented, always-available
 * boolean; autostart is an OEM-only concept living entirely outside any public API on most of the
 * manufacturers that impose it. [recommendation] is therefore a *guess*, not a sensor reading, and every
 * caller that renders it must say so — `SettingsScreen`'s own expanded card carries the caveat in words
 * (`settings_autostart_limitation_note`) rather than only in this doc comment, because a green circle
 * with no caveat reads as "verified" to an operator who never opens this file.
 *
 * The guess: [Build.MANUFACTURER] against a small, hand-maintained list of OEMs known to restrict
 * autostart by default (Xiaomi, Huawei, Oppo, vivo, Realme — the reverse-engineered `ComponentName`
 * intents [settingsTarget] returns for each are documented nowhere by their own manufacturers; they come
 * from community projects such as `dontkillmyapp.com` and the `AutoStarter` library, and can stop
 * resolving on any OS update with no changelog announcing it). Samsung and any unrecognised manufacturer
 * default to [DeviceModeStatus.Ok] — Samsung's own OneUI has no separate autostart manager the way the
 * five listed OEMs do, and an unrecognised manufacturer is assumed to behave like stock AOSP rather than
 * flagged on a guess this class has no basis for.
 *
 * Two pure, `Build`-free functions ([autostartRecommendationFor]/[autostartTargetFor]) carry the actual
 * decision so a plain JUnit test can drive every manufacturer string directly — this class's own two
 * methods exist only to supply the live [Build.MANUFACTURER] value, the same "thin wrapper around one
 * static platform read" shape [AgoChatApplication.isMainProcess][ago.chat.android.AgoChatApplication]
 * already is for `ActivityManager.runningAppProcesses`.
 */
public interface AutostartAdvisor {
    public fun recommendation(): DeviceModeStatus

    public fun settingsTarget(): AutostartSettingsTarget
}

@Singleton
public class ManufacturerAutostartAdvisor
    @Inject
    constructor() : AutostartAdvisor {
        override fun recommendation(): DeviceModeStatus = autostartRecommendationFor(Build.MANUFACTURER)

        override fun settingsTarget(): AutostartSettingsTarget = autostartTargetFor(Build.MANUFACTURER)
    }

/** The manufacturers this app currently knows restrict autostart by default — `docs/backlog/26-128-*.md`'s
 * own list, kept as one `Set` so [autostartRecommendationFor] and [autostartTargetFor] can never disagree
 * about which OEMs are "known". Matched case-insensitively: [Build.MANUFACTURER] is documented to vary in
 * case by device ("Xiaomi", "HUAWEI", "vivo" have all been observed in the wild). */
private val RESTRICTIVE_MANUFACTURERS = setOf("xiaomi", "huawei", "oppo", "vivo", "realme")

internal fun autostartRecommendationFor(manufacturer: String): DeviceModeStatus =
    if (manufacturer.lowercase() in RESTRICTIVE_MANUFACTURERS) DeviceModeStatus.NeedsAttention else DeviceModeStatus.Ok

/**
 * The reverse-engineered `ComponentName` per manufacturer — none of these five are documented by their
 * own vendor; each is the community-sourced value `docs/backlog/26-128-*.md` names. Oppo and Realme share
 * one entry because Realme UI is still ColorOS-derived on the devices this was checked against, the
 * *least* certain of the five mappings for exactly that reason (a Realme UI version that has since
 * diverged from ColorOS's own settings package would silently miss, which is why [SettingsScreen]'s own
 * button click is wrapped in a try/catch with a plain-text fallback rather than trusted outright).
 */
internal fun autostartTargetFor(manufacturer: String): AutostartSettingsTarget =
    when (manufacturer.lowercase()) {
        "xiaomi" ->
            AutostartSettingsTarget.OemComponent(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
        "huawei" ->
            AutostartSettingsTarget.OemComponent(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            )
        "oppo", "realme" ->
            AutostartSettingsTarget.OemComponent(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            )
        "vivo" ->
            AutostartSettingsTarget.OemComponent(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            )
        else -> AutostartSettingsTarget.None
    }
