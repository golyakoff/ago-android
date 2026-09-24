package ago.chat.android.core.domain.analytics

/**
 * `26-70`: `ago-console`'s own `formatCountComparison` (`src/analytics/comparison.ts`), ported as a
 * *computation* rather than as a formatted string — the one real difference between the two ports, and
 * a deliberate one.
 *
 * The console's version returns finished prose because its call sites hand it a `ConsoleStrings` bag it
 * can interpolate. On Android the equivalent wording lives in `strings.xml`, and this module has no
 * Android dependency at all (`:core:domain`'s own "no framework" rule), so the split is: **the decision
 * procedure here, the wording at the call site.** That is also the split that makes the procedure
 * testable — the branch that matters (a zero previous window has no percentage) is asserted in a plain
 * JVM test rather than by reading a rendered sentence.
 *
 * Shared rather than restated per report, unlike this app's own `startOfDayIso`/`endOfDayIso` pair
 * (four lines, no branching, copied on purpose): this has several branches that
 * `26-70`..`26-74` must all get identically right, and a wrong copy would not fail loudly — it would
 * just show a slightly different percentage on one report than another for the same figure. That is the
 * same reasoning `comparison.ts`'s own header comment gives for sharing it across the console's four
 * report pages.
 */
public data class CountComparison(
    /** The preceding window's own count, verbatim. A `0` here is a real "nothing happened last period"
     * fact, never a missing-data case — the server always computes a previous window. */
    public val previous: Int,
    /** [current] − [previous]. Negative is an ordinary outcome, not an error state. */
    public val delta: Int,
    /**
     * The change as a percentage of [previous], or `null` when [previous] is `0` — there is no
     * percentage of nothing, and rendering one as `0%` or `∞` would both be inventions. The call site
     * chooses different wording for that case rather than printing a number nobody computed.
     */
    public val relativePercent: Double?,
) {
    /** `true` when nothing moved at all. Its own property rather than a `delta == 0` check repeated at
     * every call site, because the *wording* for "unchanged" differs from the wording for a signed
     * delta and the two must not be chosen by two slightly different tests. */
    public val isUnchanged: Boolean get() = delta == 0
}

/** [CountComparison]'s own doc comment carries the reasoning; this is the whole computation. */
public fun compareCounts(
    current: Int,
    previous: Int,
): CountComparison {
    val delta = current - previous
    return CountComparison(
        previous = previous,
        delta = delta,
        relativePercent = if (previous == 0) null else (delta.toDouble() / previous.toDouble()) * 100.0,
    )
}
