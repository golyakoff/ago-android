package ago.chat.android.shell

import ago.chat.android.analytics.AnalyticsRoute
import ago.chat.android.analytics.ConversionReportRoute
import ago.chat.android.analytics.SiteAnalyticsRoute
import ago.chat.android.analytics.TagBreakdownReportRoute
import ago.chat.android.core.domain.navigation.AnalyticsReport
import ago.chat.android.core.domain.navigation.visibleAnalyticsReports
import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * `26-70`: Аналитика's own one-level-deep sub-navigation — «Мои показатели» (`26-57`) as the landing
 * screen, and the administrator reports behind its app-bar overflow.
 *
 * ## Back-button contract clause 2
 *
 * "Back from any sub-screen returns to the list it was opened from, not to the previous bottom-bar
 * destination" (`navigation.md`). [openReport] is this host's own tiny, hand-rolled sub-navigation
 * state — the identical shape [MoreScreen]'s `openRowId` and [ConversationsTabHost]'s
 * `openConversationId` already are, one level deep because nothing under Аналитика nests further. The
 * [BackHandler] below is `enabled` only while a report is open, so system back is consumed *here*,
 * landing on Аналитика, for exactly as long as that is true; once [openReport] is `null` this handler is
 * disabled and back falls through to `AppShellScreen`'s own `NavHost`, which is clause 3's job, not this
 * host's.
 *
 * **Not a `NavHost` of its own.** A nested graph would give each report a route string, a back stack
 * entry and a `ViewModelStore` that outlives leaving it — three mechanisms none of these screens needs,
 * and a second back-dispatcher participant to reason about alongside the shell's. The two screens
 * already inside this app that drill one level deep both made the same call, and this is the third.
 *
 * ## The extension point for `26-71`..`26-74`
 *
 * [reportScreen] below is `when`-exhaustive over [AnalyticsReport]. Adding the next report is: one enum
 * member in `:core:domain` (carrying its own permission), one string resource behind
 * `AnalyticsReport.labelRes()`, and one branch here. The first of those three breaks the build until the
 * other two exist — which is exactly the property `26-58` asked for, "an entry exists for a screen that
 * exists", enforced by the compiler rather than by a reviewer noticing.
 *
 * ## Why the gate is computed here rather than inside the screen
 *
 * [visibleAnalyticsReports] is called once, at the only place that already holds the operator's whole
 * permission set, and the *list* is what travels down — the identical "the caller who already has the
 * permissions computes the answer" split [AppShellContent][AppShellScreen] draws one level up for
 * Записи's own two booleans and [visibleBottomDestinations] draws for the bar itself. The screen then has
 * no permission logic in it at all, and no way to disagree with the bar about what this operator can see.
 */
@Composable
internal fun AnalyticsTabHost(
    permissions: OperatorPermissions,
    hubConnectionState: OperatorHubConnectionState,
    operatorDisplayName: String?,
    operatorEmail: String?,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    val reports = remember(permissions) { visibleAnalyticsReports(permissions) }

    // The report's *name*, not the enum itself: `rememberSaveable` needs a type the default saver can
    // write, and a plain `String` survives process death for free - the same reason `MoreScreen` saves
    // its own row id rather than a richer value. A name that no longer resolves (an operator whose
    // permissions changed while the process was away, or a report withdrawn in a later version) simply
    // falls back to the landing screen below, which is the honest outcome rather than a crash.
    var openReport by rememberSaveable { mutableStateOf<String?>(null) }
    val currentlyOpen = reports.firstOrNull { it.name == openReport }

    // Enabled on what is actually *drawn*, never merely on what is stored. The two can disagree for one
    // composition - a restored `openReport` naming a report this operator may no longer open resolves
    // to `null` above and falls back to the landing screen - and keying the handler off the stored value
    // would arm it over a screen it has nothing to return from, swallowing one back press for no reason.
    BackHandler(enabled = currentlyOpen != null) { openReport = null }

    if (currentlyOpen == null) {
        AnalyticsRoute(
            hubConnectionState = hubConnectionState,
            onOpenSettings = onOpenSettings,
            onSignOut = onSignOut,
            reports = reports,
            onOpenReport = { report -> openReport = report.name },
            operatorDisplayName = operatorDisplayName,
            operatorEmail = operatorEmail,
        )
    } else {
        reportScreen(report = currentlyOpen, onBack = { openReport = null })
    }
}

/**
 * The one place a report is turned into a screen. Exhaustive on purpose — see [AnalyticsTabHost]'s own
 * doc comment for why that exhaustiveness is this menu's whole safety property.
 */
@Composable
private fun reportScreen(
    report: AnalyticsReport,
    onBack: () -> Unit,
) {
    when (report) {
        AnalyticsReport.Site -> SiteAnalyticsRoute(onBack = onBack)
        AnalyticsReport.Conversion -> ConversionReportRoute(onBack = onBack)
        AnalyticsReport.TagBreakdown -> TagBreakdownReportRoute(onBack = onBack)
    }
}
