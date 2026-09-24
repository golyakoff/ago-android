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
 * rather than as tabs or a picker. `26-71` («Конверсия»), `26-72` («По меткам»), `26-73` («Воронка
 * записи») and `26-74` («Показы телефонов») are the second through fifth of those five, landed one at a
 * time. That order-of-landing is `buildMoreRows`'s own rule (`ago.chat.android.shell.MoreScreen`),
 * restated for this menu: **a row exists for a screen that exists.** An overflow that opens onto a name
 * with nothing behind it is the same inert-control shape `26-15`/`26-40` refused twice.
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
 * The first four reports are all gated on `site:configure` in the console today
 * (`ago-console/src/shell/consoleNav.ts`), so a single shared constant would have worked for them alone.
 * It is a per-member field instead because the mockup always drew a fifth sibling on a different
 * permission (`-- "⋮ · calendar:configure" --> Reveals`), and `26-74` below is that sibling landing on
 * exactly the gate the mockup named — a shared constant would have needed reshaping the moment this
 * member arrived, rather than simply adding a line to it.
 */
public enum class AnalyticsReport(
    public val permission: String,
) {
    /** `26-70`: «По сайту» — `GET /api/v1/conversations/analytics`, the console's own `/analytics`. */
    Site(Permission.SITE_CONFIGURE),

    /** `26-71`: «Конверсия» — `GET /api/v1/conversations/conversion-report`, the console's own
     * `/analytics/conversion`. Gated identically to [Site] today; a field of its own rather than a
     * shared constant for the reason this enum's own doc comment gives — the mockup already draws a
     * sibling report on a different permission. */
    Conversion(Permission.SITE_CONFIGURE),

    /** `26-72`: «По меткам» — `GET /api/v1/conversations/tag-breakdown-report`, the console's own
     * `/analytics/tags`. Gated identically to [Site]/[Conversion] today, for the identical "a field of
     * its own anyway" reason [Conversion]'s own doc comment gives. */
    TagBreakdown(Permission.SITE_CONFIGURE),

    /** `26-73`: «Воронка записи» — `GET /api/v1/conversations/module-flow-report`, the console's own
     * `/analytics/booking-flow`. Gated identically to [Site]/[Conversion]/[TagBreakdown] today, for the
     * identical "a field of its own anyway" reason [Conversion]'s own doc comment gives. */
    BookingFunnel(Permission.SITE_CONFIGURE),

    /** `26-74`: «Показы телефонов» — `GET /api/v1/console/contacts/phone-reveals`, the console's own
     * `/calendar/phone-reveals`. **The one member whose gate actually differs**: `calendar:configure`,
     * not `site:configure` — the mockup's own sibling gate this enum's own doc comment has named since
     * `26-70`, and the reason a shared constant was never adopted for the first four. An operator can
     * hold this permission and none of the other four, or the reverse, and each is meant to see exactly
     * the entries their own permission set earns (`docs/backlog/26-74-*.md`'s own Scope item 2). */
    PhoneReveals(Permission.CALENDAR_CONFIGURE),
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
