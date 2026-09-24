package ago.chat.android.analytics

import ago.chat.android.R
import ago.chat.android.core.domain.navigation.AnalyticsReport
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * `26-70`: Аналитика's own `⋮` — the one menu all five administrator reports (`26-70`..`26-74`) arrive
 * behind, built once here.
 *
 * ## The rule that decides whether it is drawn at all
 *
 * **An empty menu is never drawn, and a drawn menu is never disabled.** [reports] arrives already
 * filtered by [ago.chat.android.core.domain.navigation.visibleAnalyticsReports], so this composable's
 * whole gate is its first line: nothing to open, nothing to tap. That is the same "hide, don't disable"
 * rule the thread screen's attachment control and `MoreScreen`'s own `buildMoreRows` follow, and the
 * same one the console's nav takes (`consoleNav.ts` omits an item an identity cannot use rather than
 * greying it). An overflow that opens an empty menu is precisely the inert-control shape `26-15`
 * rejected and `26-40` refused to add a second time.
 *
 * The consequence worth stating plainly: an operator without `site:configure` sees Аналитика exactly as
 * before this item — «Мои показатели», no `⋮`, nothing hinting at a door they cannot open.
 *
 * ## Why this composable knows nothing about which reports exist
 *
 * It takes a list and a callback. Adding «Конверсия» (`26-71`) or «Показы телефонов» (`26-74`) touches
 * [AnalyticsReport] (one enum member, carrying its own gate) and
 * [ago.chat.android.shell.AnalyticsTabHost]'s two exhaustive `when`s (one label, one screen) — and
 * nothing here at all. That is the whole point of putting the list in `:core:domain`: this file has no
 * `when` to forget to extend, and the ones that exist fail to compile until the new entry has both a
 * name and somewhere to go.
 *
 * ## Shape copied, not invented
 *
 * [AgoIcons.MoreVertical] (the mockup's own transposed `i-dots` sprite), a plain `remember` for
 * `expanded` rather than `rememberSaveable` — a menu left open across process death is not state worth
 * restoring — and **the menu closed before the callback runs**, never after: the callback here navigates,
 * so a `setExpanded` sequenced after it would land on a composition that is already being torn down.
 * All three are `ConversationListOverflowMenu`'s own decisions (`26-32`, since retired into
 * [ago.chat.android.ui.components.AccountAvatarAction], which restates the same three).
 */
@Composable
internal fun AnalyticsReportsOverflowMenu(
    reports: List<AnalyticsReport>,
    onOpenReport: (AnalyticsReport) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (reports.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }, modifier = modifier) {
        Icon(
            imageVector = AgoIcons.MoreVertical,
            contentDescription = stringResource(R.string.analytics_reports_menu_action),
        )
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        reports.forEach { report ->
            DropdownMenuItem(
                text = { Text(text = stringResource(report.labelRes())) },
                trailingIcon = { Icon(imageVector = AgoIcons.ChevronRight, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenReport(report)
                },
            )
        }
    }
}

/**
 * The one place a report's name is chosen. Exhaustive on purpose: a new [AnalyticsReport] member stops
 * this file compiling until it is given a string resource, which is the forcing function that keeps the
 * menu from ever listing something unnamed.
 *
 * Lives in `:app` rather than on the `:core:domain` enum for the same reason
 * `BottomDestination.labelRes()` does — an Android string id is a UI detail that module has no business
 * knowing (`:core:domain`'s own "no Android, no framework" rule). The alternative, putting an `Int` on
 * the enum, would make `:core:domain` depend on the app's generated `R` class and end the module split
 * outright.
 */
@Composable
internal fun AnalyticsReport.labelRes(): Int =
    when (this) {
        AnalyticsReport.Site -> R.string.analytics_report_site
    }
