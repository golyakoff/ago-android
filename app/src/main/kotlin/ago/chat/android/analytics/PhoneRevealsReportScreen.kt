package ago.chat.android.analytics

import ago.chat.android.R
import ago.chat.android.bookings.EmptyBody
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.bookings.RefusalBody
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.PhoneReveal
import ago.chat.android.ui.components.IdentifierText
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `26-74`: «Показы телефонов» — the fifth and last of the five administrator reports behind Аналитика's
 * own overflow, and the only one that is **a record of what people did rather than a count of what
 * happened** (`docs/backlog/26-74-*.md`'s own Found section). Obtains its own
 * [PhoneRevealsReportViewModel] via [hiltViewModel], the identical wiring every other report route
 * already establishes; because [ago.chat.android.shell.AnalyticsTabHost] composes this only while the
 * report is open, that view model — and its first [ago.chat.android.core.domain.bookings.BookingsApi.fetchPhoneReveals]
 * call — come into existence only when an operator actually asks for the report.
 */
@Composable
public fun PhoneRevealsReportRoute(
    onBack: () -> Unit,
    viewModel: PhoneRevealsReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PhoneRevealsReportScreen(
        state = state,
        onRetry = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onBack = onBack,
    )
}

/**
 * The stateless half. Unlike every sibling report ([ConversionReportScreen] et al.), there is no date
 * range control at all here — `docs/backlog/26-74-*.md`'s own Scope names no range, only a keyset page
 * (this report's console sibling, `CalendarPhoneRevealsPage.tsx`, has no date filter either).
 *
 * **No separate "forbidden" render.** `docs/backlog/26-74-*.md`'s own Done-when lists forbidden,
 * "calendar not configured" and an empty trail as three states that must all be reachable and visibly
 * distinct — the console draws its own `CalendarAccessRefusal` for the first because it is reachable by
 * URL regardless of the nav. This app has no such path: [AnalyticsReport.PhoneReveals]'s own
 * `calendar:configure` gate is what [ago.chat.android.analytics.visibleAnalyticsReports] filters the
 * overflow menu by before this screen is ever composed
 * ([ago.chat.android.shell.AnalyticsTabHost]'s own doc comment on why the gate is computed once, at the
 * only place already holding the whole permission set) — the identical "hide, don't render a refusal"
 * shape every other admin-only entry on this same menu already takes. [NotConfigured] and an empty
 * [PhoneRevealsReportUiState.Loaded] are the two states an operator who *can* open this screen can
 * actually reach, and [PhoneRevealsReportScreen] renders each as its own distinct body below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneRevealsReportScreen(
    state: PhoneRevealsReportUiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.analytics_report_phone_reveals)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    PhoneRevealsReportUiState.Loading -> LoadingBody()

                    // The identical fact, and the identical sentence, Записи already renders for this
                    // deployment's own AGO Calendar backend not existing at all
                    // (`bookings_not_configured`'s own doc comment) — never a fourth wording for one fact.
                    PhoneRevealsReportUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))

                    is PhoneRevealsReportUiState.Failed ->
                        RefusalBody(
                            reason = state.reason,
                            onRetry = onRetry,
                            unexpectedMessageRes = R.string.analytics_phone_reveals_load_failed_unexpected,
                        )

                    is PhoneRevealsReportUiState.Loaded ->
                        // `docs/backlog/26-74-*.md`'s own Done-when: a genuinely empty trail renders a
                        // stated empty message, visibly different from both states above.
                        if (state.reveals.isEmpty()) {
                            EmptyBody(stringResource(R.string.analytics_phone_reveals_empty))
                        } else {
                            PhoneRevealsList(state = state, onLoadMore = onLoadMore)
                        }
                }
            }
        }
    }
}

/**
 * A flat card list, one card per reveal — `docs/backlog/26-74-*.md`'s own Scope item 4: "the mockup
 * draws no frame for this screen itself... a phone-shaped list", the same shape
 * [ago.chat.android.bookings.ContactsScreen.kt][ago.chat.android.bookings.ContactsBody]'s own card list
 * already draws for a different Записи segment. [PhoneReveal.id] keys the list — the server's own row
 * identity, never the composite of its four visible fields.
 */
@Composable
private fun PhoneRevealsList(
    state: PhoneRevealsReportUiState.Loaded,
    onLoadMore: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(state.reveals, key = { it.id }) { reveal ->
            PhoneRevealCard(reveal = reveal, zone = zone)
            HorizontalDivider()
        }

        // `docs/backlog/26-74-*.md`'s own Scope item 5: the control disappears once `nextBefore` is
        // `null` — never disabled, the same hide-rather-than-grey rule every other paging/menu control in
        // this app already takes ([AnalyticsReportsOverflowMenu]'s own doc comment).
        if (state.nextBefore != null) {
            item {
                LoadMoreFooter(loading = state.loadingMore, failed = state.loadMoreFailed, onLoadMore = onLoadMore)
            }
        }
    }
}

@Composable
private fun LoadMoreFooter(
    loading: Boolean,
    failed: BookingsQueueFailure?,
    onLoadMore: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A failed page leaves every row already on screen exactly as it was, and is shown beside the
        // control rather than in place of the list - the identical "never hides the rows the operator was
        // just looking at" rule `ago.chat.android.bookings.ActionErrorBanner`'s own doc comment states for
        // a different screen's own in-place failure.
        failed?.let { reason ->
            Text(
                text = loadMoreFailureMessage(reason),
                style = MaterialTheme.typography.bodySmall,
                color = agoStatusColors().dangerText,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Button(onClick = onLoadMore, enabled = !loading) {
            Text(
                text =
                    stringResource(
                        if (loading) R.string.analytics_phone_reveals_loading_more else R.string.analytics_phone_reveals_load_more,
                    ),
            )
        }
    }
}

@Composable
private fun loadMoreFailureMessage(reason: BookingsQueueFailure): String =
    when (reason) {
        BookingsQueueFailure.Transport -> stringResource(R.string.bookings_load_failed_transport)
        BookingsQueueFailure.Unexpected -> stringResource(R.string.analytics_phone_reveals_load_failed_unexpected)
    }

/**
 * One reveal: when, which customer, which operator, which surface — `docs/backlog/26-74-*.md`'s own
 * Scope item 4, in that exact order (the console's own four columns, `CalendarPhoneRevealsPage.tsx`).
 *
 * **[reveal.customerId]/[reveal.operatorId] render through [IdentifierText], never a name** — neither is
 * carried on the wire at all ([PhoneReveal]'s own doc comment), so there is nothing here to resolve a
 * name from even if this screen wanted to. [reveal.surface] renders exactly as the server sent it — a
 * plain string this app has no closed vocabulary for beyond its own two callers
 * ([ago.chat.android.core.domain.bookings.BookingRevealSurface]'s own doc comment: a console-originated
 * value is just as valid a [PhoneReveal.surface] as either of this app's own).
 */
@Composable
private fun PhoneRevealCard(
    reveal: PhoneReveal,
    zone: ZoneId,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        PhoneRevealDetailRow(label = stringResource(R.string.analytics_phone_reveals_when_label)) {
            Text(text = occurredAtLabel(reveal.occurredAt, zone), style = MaterialTheme.typography.bodyMedium)
        }
        PhoneRevealDetailRow(
            label = stringResource(R.string.analytics_phone_reveals_customer_label),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            IdentifierText(id = reveal.customerId, style = MaterialTheme.typography.bodyMedium)
        }
        PhoneRevealDetailRow(
            label = stringResource(R.string.analytics_phone_reveals_operator_label),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            IdentifierText(id = reveal.operatorId, style = MaterialTheme.typography.bodyMedium)
        }
        PhoneRevealDetailRow(
            label = stringResource(R.string.analytics_phone_reveals_surface_label),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(text = reveal.surface, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** [ago.chat.android.bookings.BookingsScreen]'s own `BookingDetailRow` shape, restated locally rather
 * than imported — that composable is `private` to its own file, and this is this file's only caller of
 * the shape. */
@Composable
private fun PhoneRevealDetailRow(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        content()
    }
}

/**
 * Date and time together, never a bare clock time — unlike a same-day schedule
 * ([ago.chat.android.bookings.BookingsScreen]'s own `clockTimeOrNull`), this trail is paged across
 * however much history the tenant has, so a bare `HH:mm` would be ambiguous the moment two rows land on
 * different days. `null` for anything that fails to parse falls back to
 * [R.string.analytics_no_data_value] — the same "never invented, rendered honestly" posture every
 * `*OrNull` formatter in this app already takes, restated as a stated placeholder here since *this* card
 * has no second field to fall back to the way a duration/countdown row does.
 *
 * The date is rendered in Russian regardless of the app's own language setting — [ago.chat.android.analytics.AnalyticsDateRange]'s
 * own `DATE_STAMP_FORMAT` already makes this same call for every other report's own range label, and
 * this screen does not reopen that decision.
 */
@Composable
private fun occurredAtLabel(
    occurredAt: String,
    zone: ZoneId,
): String =
    runCatching { OffsetDateTime.parse(occurredAt).atZoneSameInstant(zone).format(OCCURRED_AT_FORMAT) }
        .getOrNull() ?: stringResource(R.string.analytics_no_data_value)

private val OCCURRED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru"))
