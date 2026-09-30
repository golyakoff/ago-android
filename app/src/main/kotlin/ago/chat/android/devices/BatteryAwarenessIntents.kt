package ago.chat.android.devices

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * `26-137` (fixing `26-128`): opens **AGO Chat's own** per-app battery-optimisation screen directly —
 * `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` scoped to `package:$packageName`, the identical
 * intent [ago.chat.android.MainActivity]'s own first-launch exemption launcher already fires (and the
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` manifest permission already declared for it). `26-128` shipped
 * `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` here instead — the permission-free *global* list — which on
 * a real MIUI device dropped the operator onto "Расход заряда батареи приложением", forcing them to hunt
 * for AGO Chat and drill in themselves; the per-app intent opens the «С оптимизацией»/«Без ограничений»
 * choice for this app in one step.
 *
 * The two fallbacks below are a graceful degradation, not defensive boilerplate: an OEM build that does not
 * resolve the per-app request activity falls back to `26-128`'s own global list
 * (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), and failing even that, to the always-present App-info
 * screen (`ACTION_APPLICATION_DETAILS_SETTINGS`) where the battery entry lives one tap deeper — the same
 * "no crash, no loop" contract [ago.chat.android.MainActivity]'s own launcher catch already keeps, extended
 * to prefer the most direct screen that actually resolves rather than silently doing nothing.
 *
 * Shared between [ago.chat.android.shell.SettingsRoute]'s own expanded «Режим работы» card and the
 * first-launch sheet, which open the identical screen for the identical reason.
 */
public fun openBatteryOptimizationSettings(context: Context) {
    val packageUri = Uri.parse("package:${context.packageName}")
    startFirstResolvable(
        context,
        listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        ),
    )
}

/**
 * `26-137`: opens this app's own App-info screen (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`), the one
 * screen every Android build resolves. It is where MIUI keeps «Приостановить работу приложения, если оно не
 * используется» — a toggle Android exposes no reliable way to read or deep-link to on its own, so the
 * caller can only recommend turning it off and land the operator on the screen that holds it, never assert
 * or flip it. The `try`/`catch` is the identical fail-quiet contract [openBatteryOptimizationSettings]
 * above keeps.
 */
public fun openAppInfoSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
    } catch (missing: ActivityNotFoundException) {
        // A build with no App-info screen at all - the identical "no crash, no loop" contract as above.
    }
}

/**
 * Starts the first [candidates] intent that resolves, in order — a per-app screen preferred over a global
 * list preferred over App-info. A candidate that does not resolve (its activity is absent, or an OEM guards
 * it behind a signature-level permission this app cannot hold) is skipped rather than crashing the app;
 * exhausting the list does nothing, the same fail-quiet outcome [ago.chat.android.MainActivity]'s own
 * battery launcher already accepts for a build that resolves none of them.
 *
 * `26-328`: took a `List<Intent>` rather than `vararg` so [openAutostartSettings] can build its own ordered
 * candidate list ([autostartLaunchAttempts]) and hand it here unchanged, instead of needing an array-spread
 * at the call site for a list already computed at runtime.
 */
private fun startFirstResolvable(
    context: Context,
    candidates: List<Intent>,
) {
    for (intent in candidates) {
        try {
            context.startActivity(intent)
            return
        } catch (missing: ActivityNotFoundException) {
            // Try the next, more widely-supported candidate.
        } catch (denied: SecurityException) {
            // Some OEM builds guard a battery activity behind a signature-level permission this app cannot
            // hold - skip to the next candidate rather than crash, the same reasoning as the missing case.
        }
    }
}

/**
 * `26-328`: one step of the ordered plan [autostartLaunchAttempts] builds for a given
 * [AutostartSettingsTarget] — kept as data, not as an `Intent` directly, so the *ordering* decision stays
 * pure and unit-testable (an `Intent` needs the Android framework to even construct); [openAutostartSettings]
 * is the only place that turns a step into a real `Intent`, since [Package] needs a live
 * `PackageManager.getLaunchIntentForPackage` call this sealed interface itself must never depend on (rule 2 -
 * no platform I/O in a type Application/Domain-shaped code could otherwise share).
 */
internal sealed interface AutostartLaunchAttempt {
    /** Launch a specific, named `Activity` — one [AutostartSettingsTarget.OemComponent] candidate. */
    data class Component(
        val target: AutostartSettingsTarget.OemComponent,
    ) : AutostartLaunchAttempt

    /** Launch [packageName]'s own launcher intent (its main entry point), not a guessed internal screen. */
    data class Package(
        val packageName: String,
    ) : AutostartLaunchAttempt

    /** The one screen every Android build resolves — see [openAppInfoSettings]'s own doc comment. */
    data object AppInfo : AutostartLaunchAttempt
}

/**
 * `26-328`: the ordered plan [openAutostartSettings] executes for [target], as pure data — the exact thing a
 * plain JUnit test can drive without touching `Intent`/`Context` at all, matching
 * [autostartRecommendationFor]/[autostartTargetFor]'s own "decision as a pure function" shape in
 * `AutostartAdvisor.kt`.
 *
 * [AutostartSettingsTarget.OemComponent] (Xiaomi, Oppo/Realme, vivo) stays exactly as narrow as before this
 * item: one candidate, no further fallback if it fails to resolve — `docs/backlog/26-128-*.md`'s original
 * contract, which `26-328`'s own Scope only asked to harden for Huawei, not to widen for the other three
 * without a matching multi-version report justifying it.
 *
 * [AutostartSettingsTarget.OemComponentChain] (Huawei) tries every known component first, then its
 * [AutostartSettingsTarget.OemComponentChain.fallbackPackage]'s own launcher intent (the Phone Manager app
 * itself, when every specific screen has moved or is blocked), then [AutostartLaunchAttempt.AppInfo] as the
 * last resort that is guaranteed to resolve — so a chain target, unlike a single [OemComponent], always ends
 * up *somewhere* real rather than silently doing nothing.
 */
internal fun autostartLaunchAttempts(target: AutostartSettingsTarget): List<AutostartLaunchAttempt> =
    when (target) {
        AutostartSettingsTarget.None -> emptyList()
        is AutostartSettingsTarget.OemComponent -> listOf(AutostartLaunchAttempt.Component(target))
        is AutostartSettingsTarget.OemComponentChain ->
            target.candidates.map { AutostartLaunchAttempt.Component(it) } +
                listOfNotNull(target.fallbackPackage?.let { AutostartLaunchAttempt.Package(it) }) +
                listOf(AutostartLaunchAttempt.AppInfo)
    }

/**
 * `26-128`/`26-328`: opens the OEM-specific autostart screen [target] names, when one is known.
 * [AutostartSettingsTarget.None] (Samsung, or any unrecognised manufacturer) means there is nothing to
 * launch — callers are expected to have already chosen not to show a button in that case
 * (`SettingsScreen`'s own autostart expand card renders plain text instead), so this function silently
 * does nothing rather than being called at all for that case; it is not itself the guard.
 *
 * The `try`/`catch` inside [startFirstResolvable] is load-bearing, not defensive boilerplate: every
 * [AutostartSettingsTarget.OemComponent] [autostartTargetFor] can return is reverse-engineered and
 * undocumented by its own vendor, and can stop resolving on any OS update with no warning —
 * `docs/backlog/26-128-*.md`'s own call for "a plain-text fallback caption if it doesn't resolve" (now also
 * `docs/backlog/26-328-*.md`'s always-visible manual-steps caption) is why every call site keeps its own
 * static caption visible regardless of whether this call actually launches anything, rather than this
 * function trying to report failure back up.
 *
 * `26-328`: for [AutostartSettingsTarget.OemComponentChain] this is no longer "one intent, one try" — see
 * [autostartLaunchAttempts] for the ordered plan this function only turns into real `Intent`s and hands to
 * [startFirstResolvable] unchanged.
 */
public fun openAutostartSettings(
    context: Context,
    target: AutostartSettingsTarget,
) {
    val intents =
        autostartLaunchAttempts(target).mapNotNull { attempt ->
            when (attempt) {
                is AutostartLaunchAttempt.Component ->
                    Intent().apply { setClassName(attempt.target.packageName, attempt.target.className) }
                is AutostartLaunchAttempt.Package ->
                    context.packageManager.getLaunchIntentForPackage(attempt.packageName)
                AutostartLaunchAttempt.AppInfo ->
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            }
        }
    startFirstResolvable(context, intents)
}
