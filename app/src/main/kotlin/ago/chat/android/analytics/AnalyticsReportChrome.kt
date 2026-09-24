package ago.chat.android.analytics

import ago.chat.android.R
import ago.chat.android.core.domain.analytics.CountComparison
import ago.chat.android.core.domain.analytics.formatDurationSeconds
import ago.chat.android.ui.components.SectionLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/*
 * `26-70`: the pieces every Аналитика report is built from - a card, a key/value row, an empty note, a
 * spinner and a refusal - lifted out of `AnalyticsScreen` (`26-57`) once a *second* report needed the
 * identical five. `26-71`..`26-74` each need them too, which is what turned five private composables
 * into a shared, `internal` file rather than a copy per screen.
 *
 * Deliberately NOT in `ui/components`. Nothing here is general: the card is the mockup's own
 * `.card`/`.kv` shape *as used by this one family of screens*, and promoting it to the app's shared
 * component set would invite a sixth, unrelated screen to adopt a layout that was never designed for
 * it. `SectionLabel` - which genuinely is general, and which every one of these uses - already lives
 * there; this file is the layer between that and a report.
 */

/**
 * The mockup's own `.card` — a [SectionLabel] heading over a stack of rows. The heading is always drawn,
 * including for a card whose body turns out to be empty, because a section that vanishes entirely reads
 * as "this report has nothing to say" rather than "this particular breakdown is empty", and those are
 * different facts (`docs/backlog/26-70-*.md`'s own Done-when: "a breakdown with no rows says so for
 * itself").
 */
@Composable
internal fun AnalyticsStatCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        SectionLabel(text = title)
        ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

/** The mockup's own `.kv` row — a label in the softer ink, a bold value flush right. */
@Composable
internal fun AnalyticsKeyValueRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** What a section with nothing in it says for itself — never a page-wide empty state standing in for
 * several independent ones. */
@Composable
internal fun AnalyticsEmptySectionText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * A caveat that belongs to the numbers above or below it, not to the screen as a whole — the interval-
 * vs-conversation unit note and the "this is what the browser reported" note (`26-70`'s Scope item 5)
 * are both drawn with this. Styled the same as [AnalyticsEmptySectionText] on purpose, since both are
 * quiet prose about data rather than data, but kept as its own function so a later change to how a
 * *caveat* reads cannot silently restyle every *empty state* as well.
 */
@Composable
internal fun AnalyticsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
internal fun AnalyticsLoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * The operator's own mistake, said plainly and in the error colour — `Analytics.InvalidRange` and
 * nothing else. No retry button, because repeating the identical bad range would fail identically; the
 * date control stays on screen above this, which is where the fix actually is.
 */
@Composable
internal fun AnalyticsInlineMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Every failure other than `Analytics.InvalidRange`: a sentence this app wrote and a retry — never a
 * raw exception class name, a hostname or a status code (`26-59`). Takes the message as an already-
 * resolved [String] rather than a failure enum, because the two reports' failure enums are deliberately
 * separate types ([ago.chat.android.core.domain.analytics.SiteAnalyticsFailure]'s own doc comment) and a
 * shared composable that knew about both would reintroduce exactly the coupling those two avoid.
 */
@Composable
internal fun AnalyticsRefusalBody(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

/**
 * The second of this item's three distinguished values: **"nothing to average"**, for an average that is
 * `null` because no conversation in the bucket ever received a reply (or ever closed). Never `0`, which
 * is a real measurement, and never the "no data" value used for an absent assignment summary — that
 * third value is [SiteAnalyticsScreen]'s own `loadCellValue`.
 */
@Composable
internal fun durationOrNoAverage(seconds: Double?): String =
    seconds?.let { formatDurationSeconds(it) } ?: stringResource(R.string.analytics_no_data_value)

/**
 * Three renderings for three genuinely different situations, chosen by [CountComparison]'s own answer
 * rather than re-derived here: nothing moved, something moved against a window that was empty (so
 * there is no percentage of it), and something moved against a real count. The signs come from
 * `%+d`/`%+.1f`, never from concatenating a literal `"+"`, so a negative delta cannot end up with two
 * signs.
 *
 * `26-71`: promoted out of `SiteAnalyticsScreen` once [ago.chat.android.analytics.ConversionReportScreen]
 * needed the identical three renderings for a second bucket shape — the same "a second report needing
 * it is what earns a composable a place in this shared file" rule this file's own header comment
 * states for its other five pieces.
 */
@Composable
internal fun comparisonText(comparison: CountComparison): String {
    val signedDelta = String.format(Locale.US, "%+d", comparison.delta)
    val percent = comparison.relativePercent
    return when {
        comparison.isUnchanged -> stringResource(R.string.analytics_comparison_no_change, comparison.previous)
        percent == null -> stringResource(R.string.analytics_comparison_absolute, comparison.previous, signedDelta)
        else ->
            stringResource(
                R.string.analytics_comparison_relative,
                comparison.previous,
                signedDelta,
                String.format(Locale.US, "%+.1f", percent),
            )
    }
}

/**
 * The breakdown-table header cell every report's per-dimension table shares — bold, softer ink, up to
 * two lines before ellipsis. `26-71`: promoted out of `SiteAnalyticsScreen` once
 * [ago.chat.android.analytics.ConversionReportScreen] needed the identical shape for a table over a
 * different bucket type.
 */
@Composable
internal fun AnalyticsTableHeaderCell(
    text: String,
    width: Dp,
    alignEnd: Boolean = true,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        modifier = Modifier.width(width).padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

/** The breakdown-table body cell every report's per-dimension table shares — see
 * [AnalyticsTableHeaderCell]'s own doc comment for why this is shared rather than restated. */
@Composable
internal fun AnalyticsTableBodyCell(
    text: String,
    width: Dp,
    alignEnd: Boolean = true,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        modifier = Modifier.width(width).padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

/** Wide enough for a real operator name or a referrer host at this type size; narrow enough that the
 * first numeric column is already visible before any scrolling, so the table reads as a table rather
 * than as a list of names. Shared for the identical reason [AnalyticsTableHeaderCell] is. */
internal val AnalyticsTableLabelColumnWidth = 132.dp
internal val AnalyticsTableNumberColumnWidth = 104.dp
