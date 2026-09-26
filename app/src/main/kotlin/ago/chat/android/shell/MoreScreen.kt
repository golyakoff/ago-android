package ago.chat.android.shell

import ago.chat.android.R
import ago.chat.android.channels.InstallWidgetRoute
import ago.chat.android.channels.MaxChannelRoute
import ago.chat.android.channels.TelegramChannelRoute
import ago.chat.android.channels.VkChannelRoute
import ago.chat.android.channels.WidgetConfigRoute
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.ui.components.AccountAvatarAction
import ago.chat.android.ui.components.SectionLabel
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
 * Автоматизация, Администрирование) as headers, their items as rows, no nesting beyond that.
 *
 * **`26-77` moved Настройки out of this screen entirely** — into
 * [AccountAvatarAction][ago.chat.android.ui.components.AccountAvatarAction]'s own menu, reachable from
 * every top-level screen rather than only this one (`docs/backlog/26-77-*.md`'s own Scope: `26-16`'s
 * own brief had already named the row's placement here as "a stated exception… even if that row
 * currently points nowhere… since `26-17` hasn't landed" — a documented stopgap, not a considered
 * permanent home). `SETTINGS_ROW_ID` and its branch are gone with it; this screen no longer takes a
 * `settingsScreen` parameter at all, since nothing inside it opens Settings any more —
 * [AppShellScreen]'s own `NavHost` owns that now, one level up.
 *
 * **Every row below is honest about not existing yet.** [buildMoreRows] returns four rows —
 * Автоматизация's «Готовые ответы»/«Автоответ вне смены», Администрирование's «Операторы и роли»/
 * «Тариф и оплата» — each opening [PlaceholderDestinationScreen], because none of their own real
 * screens are built in this app yet. Каналы still has none at all, so it still does not appear
 * ([buildMoreSections]' own "a section with no rows is not returned at all" rule, ported from
 * `ago-console/src/shell/consoleNav.ts`'s `buildSection`) — unchanged from every wave before this one.
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
internal fun MoreScreen(
    hubConnectionState: OperatorHubConnectionState,
    operatorDisplayName: String?,
    operatorEmail: String?,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
    // `26-159`: whether the signed-in operator holds `site:configure` - computed once by
    // [AppShellContent] from the [ago.chat.android.core.domain.permissions.OperatorPermissions.Known] it
    // already has, and handed here as a plain `Boolean`, the identical "the caller who already has the
    // permission set computes the gate" split every other screen's own gate follows. Decides whether the
    // Каналы «Установка виджета» row is drawn at all (hide-not-disable). Defaults to `false` so the
    // back-contract tests that drive [MoreScreen] with no `site:configure` keep rendering exactly the rows
    // they did before this item.
    canConfigureSite: Boolean = false,
) {
    var openRowId by rememberSaveable { mutableStateOf<String?>(null) }
    val rows = remember(canConfigureSite) { buildMoreRows(canConfigureSite) }
    BackHandler(enabled = openRowId != null) { openRowId = null }

    val openRow = rows.firstOrNull { it.id == openRowId }
    if (openRow != null) {
        when (openRow.id) {
            // `26-159`: the one Ещё row with a real screen behind it - the chat-widget install screen,
            // mirroring `ago-console`'s own `InstallSnippetPage`. Back returns to the Ещё list (clause 2)
            // via the same `openRowId = null` this screen's own `BackHandler` above already uses.
            CHANNELS_INSTALL_ROW_ID -> InstallWidgetRoute(onBack = { openRowId = null })
            // `26-193`: Каналы → «Виджет на сайте» - the widget's own on-site appearance/behaviour/consent
            // config, a hub over its own three group editors (`docs/design/tenant-widget-android.md`).
            // Back returns to the Ещё list (clause 2) via the same `openRowId = null` every drill-in uses.
            CHANNELS_WIDGET_ROW_ID -> WidgetConfigRoute(onBack = { openRowId = null })
            // `26-189`/`C2`: Каналы → MAX - the second of the three token channels this scaffold serves,
            // identical wiring to Telegram below with its own `ChannelKind`. Back returns to the Ещё list
            // (clause 2) via the same `openRowId = null` every drill-in uses.
            CHANNELS_MAX_ROW_ID -> MaxChannelRoute(onBack = { openRowId = null })
            // `26-188`: Каналы → Telegram - a token connect/status/disconnect screen, the first of the
            // three token channels this scaffold serves. Back returns to the Ещё list (clause 2) via the
            // same `openRowId = null` every drill-in uses.
            CHANNELS_TELEGRAM_ROW_ID -> TelegramChannelRoute(onBack = { openRowId = null })
            // `26-190`/`C3`: Каналы → VK - the third and last token channel this scaffold serves, plus its
            // own shown-once callback URL/webhook secret reveal drawn inside the shared
            // [ago.chat.android.channels.ChannelConnectScreen]. Back returns to the Ещё list (clause 2) via
            // the same `openRowId = null` every drill-in uses.
            CHANNELS_VK_ROW_ID -> VkChannelRoute(onBack = { openRowId = null })
            else ->
                PlaceholderDestinationScreen(
                    title = stringResource(openRow.labelRes),
                    body = stringResource(R.string.more_placeholder_body),
                )
        }
    } else {
        MoreListScreen(
            rows = rows,
            hubConnectionState = hubConnectionState,
            operatorDisplayName = operatorDisplayName,
            operatorEmail = operatorEmail,
            onOpenSettings = onOpenSettings,
            onSignOut = onSignOut,
            onRowClick = { rowId -> openRowId = rowId },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreListScreen(
    rows: List<MoreRow>,
    hubConnectionState: OperatorHubConnectionState,
    operatorDisplayName: String?,
    operatorEmail: String?,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
    onRowClick: (String) -> Unit,
) {
    val sections = remember(rows) { buildMoreSections(rows) }
    val ungrouped = remember(rows) { rows.filter { it.section == null } }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.nav_more)) },
                    actions = {
                        AccountAvatarAction(
                            displayName = operatorDisplayName,
                            email = operatorEmail,
                            hubConnectionState = hubConnectionState,
                            onOpenSettings = onOpenSettings,
                            onSignOut = onSignOut,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    },
                )
            },
        ) { padding ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                for ((section, sectionRows) in sections) {
                    item(key = "header-${section.name}") {
                        // `26-44`: the mockup's `.slabel`, shared with `SettingsScreen`'s own section
                        // headings rather than a second inlined copy of the same five properties.
                        SectionLabel(stringResource(section.labelRes))
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
    /** `null` for a row that sits directly under Ещё, outside any of the three folded sections. No
     * row draws this shape any more since `26-77` moved Настройки into the account menu — kept
     * nullable rather than made non-optional, since `navigation.md` does not rule out Ещё ever
     * gaining another ungrouped row of its own. */
    val section: MoreSectionId?,
)

internal const val CHANNELS_INSTALL_ROW_ID: String = "channels-install"
internal const val CHANNELS_WIDGET_ROW_ID: String = "channels-widget-config"
internal const val CHANNELS_MAX_ROW_ID: String = "channels-max"
internal const val CHANNELS_TELEGRAM_ROW_ID: String = "channels-telegram"
internal const val CHANNELS_VK_ROW_ID: String = "channels-vk"
internal const val AUTOMATION_QUICK_REPLIES_ROW_ID: String = "automation-quick-replies"
internal const val AUTOMATION_AFTER_HOURS_ROW_ID: String = "automation-after-hours"
internal const val ADMINISTRATION_OPERATORS_ROW_ID: String = "administration-operators"
internal const val ADMINISTRATION_BILLING_ROW_ID: String = "administration-billing"

/** `26-77`: four rows, real at last — see this file's own top-of-file doc comment for why each still
 * opens [PlaceholderDestinationScreen] rather than a finished screen.
 *
 * `26-159`: Каналы gains its first real row — «Установка виджета» — but only when [canConfigureSite],
 * matching how `ago-console`'s own rail gates its `/channels/install` entry on `site:configure`. A
 * section with no rows is still not drawn at all ([buildMoreSections]), so an operator without the
 * permission sees no Каналы header either, exactly as before this item.
 *
 * `26-188`: Каналы gains its second real row — Telegram — under the identical gate. Row order follows
 * `docs/design/tenant-channels-android.md` §5.2 (Установка виджета · MAX · Telegram · VK · Почта).
 *
 * `26-189`/`C2`: MAX takes its own place in that order, directly after Установка виджета and before
 * Telegram - the row order stated in §5.2 is fixed regardless of the order the slices themselves land in.
 *
 * `26-190`/`C3`: VK closes out the three token channels, directly after Telegram and before the still
 * unbuilt Почта row.
 *
 * `26-193`: «Виджет на сайте» takes its own place directly after «Установка виджета» and before MAX - the
 * row order `docs/design/tenant-widget-android.md` §8.1 states (Установка виджета · **Виджет на сайте** ·
 * MAX · Telegram · VK · Почта), filling the gap the channels doc's own §5.2 deliberately left. */
internal fun buildMoreRows(canConfigureSite: Boolean = false): List<MoreRow> =
    buildList {
        if (canConfigureSite) {
            add(
                MoreRow(
                    id = CHANNELS_INSTALL_ROW_ID,
                    labelRes = R.string.channels_install_title,
                    section = MoreSectionId.Channels,
                ),
            )
            add(
                MoreRow(
                    id = CHANNELS_WIDGET_ROW_ID,
                    labelRes = R.string.widget_config_hub_title,
                    section = MoreSectionId.Channels,
                ),
            )
            add(
                MoreRow(
                    id = CHANNELS_MAX_ROW_ID,
                    labelRes = R.string.channels_max_title,
                    section = MoreSectionId.Channels,
                ),
            )
            add(
                MoreRow(
                    id = CHANNELS_TELEGRAM_ROW_ID,
                    labelRes = R.string.channels_telegram_title,
                    section = MoreSectionId.Channels,
                ),
            )
            add(
                MoreRow(
                    id = CHANNELS_VK_ROW_ID,
                    labelRes = R.string.channels_vk_title,
                    section = MoreSectionId.Channels,
                ),
            )
        }
        add(
            MoreRow(
                id = AUTOMATION_QUICK_REPLIES_ROW_ID,
                labelRes = R.string.more_automation_quick_replies_row,
                section = MoreSectionId.Automation,
            ),
        )
        add(
            MoreRow(
                id = AUTOMATION_AFTER_HOURS_ROW_ID,
                labelRes = R.string.more_automation_after_hours_row,
                section = MoreSectionId.Automation,
            ),
        )
        add(
            MoreRow(
                id = ADMINISTRATION_OPERATORS_ROW_ID,
                labelRes = R.string.more_administration_operators_row,
                section = MoreSectionId.Administration,
            ),
        )
        add(
            MoreRow(
                id = ADMINISTRATION_BILLING_ROW_ID,
                labelRes = R.string.more_administration_billing_row,
                section = MoreSectionId.Administration,
            ),
        )
    }

/** `ago-console/src/shell/consoleNav.ts`'s own `buildSection`, ported: a section with no rows is not
 * returned at all, rather than returned empty for the caller to filter — there is no "collapsed
 * section" rendering anywhere in this screen for the identical reason there is none in the console's
 * own rail. */
internal fun buildMoreSections(rows: List<MoreRow>): List<Pair<MoreSectionId, List<MoreRow>>> =
    MoreSectionId.entries.mapNotNull { section ->
        val sectionRows = rows.filter { it.section == section }
        if (sectionRows.isEmpty()) null else section to sectionRows
    }
