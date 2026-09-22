package ago.chat.android.shell

import ago.chat.android.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * `26-16`: Ещё, `navigation.md`'s own "list-of-lists" — the three folded sections (Каналы,
 * Автоматизация, Администрирование) as headers, their items as rows, no nesting beyond that, plus
 * Settings as a fourth, ungrouped row sitting directly under Ещё rather than inside any of the three
 * (`navigation.md`'s own Mermaid diagram: `More --> Set["Настройки"]`, a direct child, not routed
 * through `Ch`/`Au`/`Ad`).
 *
 * **Every one of this wave's rows is honest about not existing yet.** [buildMoreRows] returns exactly
 * one entry — Settings — because none of Каналы/Автоматизация/Администрирование's own screens are
 * built in this app yet (`26-16`'s own Out of scope names every one of them as later work), and "a row
 * exists only for a screen that exists" (`docs/backlog/26-16-*.md`'s own Scope) is not a rule this
 * wave gets to apply selectively. [buildMoreSections] is the identical `buildSection`-returns-null-then-
 * filter rule `ago-console/src/shell/consoleNav.ts` applies to its own seven sections, ported: with
 * every section's own row list empty, all three disappear, and what an operator actually sees this
 * wave is a one-row screen. That is the correct, current answer, not a bug this item leaves behind —
 * the machinery is what a later item needs when it adds the first real Каналы/Автоматизация/
 * Администрирование screen, not a placeholder list invented to look fuller than the app actually is.
 *
 * **Settings gets a stated exception to that same rule.** `docs/backlog/26-16-*.md`'s own brief:
 * "this item puts the row in Ещё, even if that row currently points nowhere (or a placeholder) since
 * `26-17` hasn't landed" — so [SETTINGS_ROW_ID] is drawn unconditionally. `26-17` is the item that
 * lands: opening the row now renders the real [SettingsRoute] rather than the placeholder this file
 * used to draw in its place.
 *
 * ## Back-button contract clause 2
 *
 * "Back from any Ещё screen returns to the Ещё list, not to the previous bottom-bar destination"
 * (`navigation.md`). [openRowId] is this screen's own tiny, hand-rolled sub-navigation state — the
 * identical shape [ConversationsTabHost]'s `openConversationId` already is for Диалоги, one level
 * deep because `navigation.md` states Ещё never nests further. The [BackHandler] below is `enabled`
 * only while a row is open, so system back is consumed *here*, landing on the list, for as long as
 * that is true; once [openRowId] is `null` (the Ещё list itself), this `BackHandler` is disabled and
 * back falls through to `AppShellScreen`'s own `NavHost`, which is clause 3's job, not this screen's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreScreen(settingsScreen: @Composable (onBack: () -> Unit) -> Unit) {
    var openRowId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = openRowId != null) { openRowId = null }

    when (openRowId) {
        SETTINGS_ROW_ID -> settingsScreen { openRowId = null }
        else -> MoreListScreen(onRowClick = { rowId -> openRowId = rowId })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreListScreen(onRowClick: (String) -> Unit) {
    val rows = remember { buildMoreRows() }
    val sections = remember(rows) { buildMoreSections(rows) }
    val ungrouped = remember(rows) { rows.filter { it.section == null } }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(topBar = { TopAppBar(title = { Text(text = stringResource(R.string.nav_more)) }) }) { padding ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                for ((section, sectionRows) in sections) {
                    item(key = "header-${section.name}") {
                        Text(
                            text = stringResource(section.labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(sectionRows, key = { it.id }) { row -> MoreRowItem(row, onRowClick) }
                }
                items(ungrouped, key = { it.id }) { row -> MoreRowItem(row, onRowClick) }
            }
        }
    }
}

@Composable
private fun MoreRowItem(
    row: MoreRow,
    onClick: (String) -> Unit,
) {
    Text(
        text = stringResource(row.labelRes),
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick(row.id) }
                .padding(horizontal = 16.dp, vertical = 16.dp),
    )
    HorizontalDivider()
}

// ---------------------------------------------------------------------------------- row/section model

internal enum class MoreSectionId(
    val labelRes: Int,
) {
    Channels(R.string.more_section_channels),
    Automation(R.string.more_section_automation),
    Administration(R.string.more_section_administration),
}

internal data class MoreRow(
    val id: String,
    val labelRes: Int,
    /** `null` for a row that sits directly under Ещё, outside any of the three folded sections —
     * Settings is the one row `navigation.md` draws this way. */
    val section: MoreSectionId?,
)

internal const val SETTINGS_ROW_ID: String = "settings"

/** Every row this wave actually has a screen for. See this file's own top-of-file doc comment for why
 * that is exactly one row today, and why that is the correct answer rather than an oversight. */
internal fun buildMoreRows(): List<MoreRow> =
    listOf(
        MoreRow(id = SETTINGS_ROW_ID, labelRes = R.string.more_settings_row, section = null),
    )

/** `ago-console/src/shell/consoleNav.ts`'s own `buildSection`, ported: a section with no rows is not
 * returned at all, rather than returned empty for the caller to filter — there is no "collapsed
 * section" rendering anywhere in this screen for the identical reason there is none in the console's
 * own rail. */
internal fun buildMoreSections(rows: List<MoreRow>): List<Pair<MoreSectionId, List<MoreRow>>> =
    MoreSectionId.entries.mapNotNull { section ->
        val sectionRows = rows.filter { it.section == section }
        if (sectionRows.isEmpty()) null else section to sectionRows
    }
