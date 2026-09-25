package ago.chat.android.devices

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * `26-128`: opens the system's own battery-optimisation list —
 * `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, the permission-free list every app may open,
 * deliberately *not* `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (a one-tap per-app dialog that needs the
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` manifest permission this item's own "Out of scope" refuses to
 * add). The operator finds this app in the list themselves; there is no per-app dialog this intent can
 * open without that permission. Shared between [ago.chat.android.shell.SettingsRoute]'s own expanded
 * «Режим работы» card and the first-launch sheet, which open the identical screen for the identical
 * reason, rather than each inlining its own copy of the same three lines.
 */
public fun openBatteryOptimizationSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    } catch (missing: ActivityNotFoundException) {
        // The identical "no crash, no loop" contract `MainActivity.openInBrowser`'s own catch already
        // applies - a build with no such system screen is not this app's bug to retry.
    }
}

/**
 * `26-128`: opens the OEM-specific autostart screen [target] names, when one is known.
 * [AutostartSettingsTarget.None] (Samsung, or any unrecognised manufacturer) means there is nothing to
 * launch — callers are expected to have already chosen not to show a button in that case
 * (`SettingsScreen`'s own autostart expand card renders plain text instead), so this function silently
 * does nothing rather than being called at all for that case; it is not itself the guard.
 *
 * The `try`/`catch` is load-bearing, not defensive boilerplate: every [AutostartSettingsTarget.OemComponent]
 * [autostartTargetFor] can return is reverse-engineered and undocumented by its own vendor, and can stop
 * resolving on any OS update with no warning — `docs/backlog/26-128-*.md`'s own call for "a plain-text
 * fallback caption if it doesn't resolve" is why every call site keeps its own static caption visible
 * regardless of whether this call actually launches anything, rather than this function trying to report
 * failure back up.
 */
public fun openAutostartSettings(
    context: Context,
    target: AutostartSettingsTarget,
) {
    val component = target as? AutostartSettingsTarget.OemComponent ?: return
    try {
        context.startActivity(
            Intent().apply { setClassName(component.packageName, component.className) },
        )
    } catch (missing: ActivityNotFoundException) {
        // See this function's own doc comment - a missing/renamed OEM screen is expected, not a bug.
    } catch (denied: SecurityException) {
        // Some OEM builds guard these activities behind a signature-level permission this app cannot
        // hold - the identical "expected, not a bug" reasoning as the missing-activity case above.
    }
}
