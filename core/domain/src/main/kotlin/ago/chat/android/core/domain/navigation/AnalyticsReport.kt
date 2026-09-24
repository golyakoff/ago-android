package ago.chat.android.core.domain.navigation

import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.Permission
import ago.chat.android.core.domain.permissions.holds

/**
 * `26-70`: the administrator reports reachable from Аналитика's own overflow — **exactly those whose
 * screen exists**, which this wave is one of, «По сайту».
 *
 * ## Why an enum, and why it is the extension point for `26-71`..`26-74`
 *
 * `26-58` decided all five administrator reports port to Android as destinations behind one overflow
 * rather than as tabs or a picker. Four of them (`26-71` конверсия, `26-72` метки, `26-73` воронка
 * записи, `26-74` показы телефонов) are not built yet, and this enum deliberately does not name them.
 * That is `buildMoreRows`'s own rule (`ago.chat.android.shell.MoreScreen`), restated for this menu: **a
 * row exists for a screen that exists.** An overflow that opens onto a name with nothing behind it is
 * the same inert-control shape `26-15`/`26-40` refused twice.
 *
 * Adding the next one is therefore mechanical and, importantly, *not silently incomplete*: a new member
 * here breaks the two exhaustive `when`s in `:app` that map a report to its label and to its screen
 * (`ago.chat.android.shell.AnalyticsTabHost`), so the compiler — not a reviewer's memory — is what
 * insists a new entry arrives with both. The alternative, a `List` of data objects built at a call
 * site, would compile happily with a label and no screen, which is exactly the failure this menu exists
 * to avoid.
 *
 * ## Why the gate is a field rather than a shared constant
 *
 * All four remaining reports are gated on `site:configure` in the console today
 * (`ago-console/src/shell/consoleNav.ts`), so a single shared constant would work *right now*. It is a
 * per-member field anyway because the mockup already draws a fifth sibling on a different permission
 * (`-- "⋮ · calendar:configure" --> Reveals`, `26-74`), and discovering that halfway through the next
 * item would mean reshaping this type rather than adding a line to it.
 */
public enum class AnalyticsReport(
    public val permission: String,
) {
    /** `26-70`: «По сайту» — `GET /api/v1/conversations/analytics`, the console's own `/analytics`. */
    Site(Permission.SITE_CONFIGURE),
}

/**
 * Which reports this operator's own permission set may open, in [AnalyticsReport]'s own declaration
 * order. Pure logic over [OperatorPermissions], with no Android or Compose dependency crossing this
 * function — the identical reason [visibleBottomDestinations] is a plain function in this same module:
 * the property `docs/backlog/26-70-*.md`'s Done-when needs proven ("an operator without `site:configure`
 * sees no `⋮` at all") is exactly the kind of thing worth being reachable from a plain JVM unit test.
 *
 * **An empty result means the control is not drawn — hidden, never disabled, and never an empty menu.**
 * That decision is the caller's to *render*, but it is this function's to *decide*, which is why the
 * emptiness is expressed as an ordinary empty list rather than as a nullable or a separate flag: the
 * call site's whole check is `if (reports.isEmpty()) return`, with no second rule to keep in step.
 * Hiding rather than muting matches the console's own nav, which omits an item an identity cannot use
 * rather than showing it greyed (`consoleNav.ts`), and matches this app's own thread-screen attachment
 * button and [visibleBottomDestinations].
 *
 * [OperatorPermissions.Unknown] yields nothing, the same fail-closed direction [holds] takes for every
 * other gate in this app — the safe way to guess wrong about a control is to leave it out for the
 * second or so the answer is in flight, and Аналитика itself is drawn regardless, so nothing an
 * operator needs is behind this answer.
 */
public fun visibleAnalyticsReports(permissions: OperatorPermissions): List<AnalyticsReport> =
    AnalyticsReport.entries.filter { permissions.holds(it.permission) }
