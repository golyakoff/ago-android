package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.calendarsetup.ConfiguredCalendar
import ago.chat.android.ui.components.SectionLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

/**
 * `26-142`: Записи's own «Настройка» body — the calendar roster with create/edit, the four-arm `when`
 * below the identical shape [MastersBody]/[ServicesBody] already draw, reusing
 * [LoadingBody]/[EmptyBody]/[RefusalBody]/[ActionErrorBanner] verbatim.
 *
 * `26-158`: the embed snippet and allowed-origins section that used to head this body are gone — they are a
 * chat/channel setting, not a calendar one, and now live in the «Установка виджета» screen under «Ещё»
 * ([ago.chat.android.channels.InstallWidgetScreen], `26-159`). This body now shows only Календари.
 *
 * **Working hours are not here.** The console's own Setup page carries a working-hours block; on this app
 * that is the existing Часы screen ([ago.chat.android.schedule.WorkingHoursBody]), reached from the same
 * `⋮` hub — a deliberate deviation from the console's shape, so a rule is edited in exactly one place.
 *
 * **The timezone is a localized dropdown on create, and shown nowhere on edit.** A calendar's zone is
 * fixed at creation ([ago.chat.android.core.domain.calendarsetup.CalendarSetupApi.updateCalendar] carries
 * no field for it), so offering it on an edit would promise a move that cannot happen — the same
 * create-only rule [WorkerEditForm]'s own calendar picker follows.
 */
@Composable
internal fun CalendarSetupBody(
    state: CalendarSetupUiState,
    onRetry: () -> Unit,
    onAddCalendar: () -> Unit,
    onEditCalendar: (ConfiguredCalendar) -> Unit,
    onCalendarFormChanged: (CalendarForm) -> Unit,
    onCancelCalendarEdit: () -> Unit,
    onSubmitCalendar: (CalendarForm) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state is CalendarSetupUiState.Loaded) {
            state.actionError?.let { error -> ActionErrorBanner(error = error, modifier = Modifier.fillMaxWidth()) }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                CalendarSetupUiState.Loading -> LoadingBody()
                CalendarSetupUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
                is CalendarSetupUiState.Failed ->
                    RefusalBody(
                        reason = state.reason,
                        onRetry = onRetry,
                        unexpectedMessageRes = R.string.calendar_setup_load_failed_unexpected,
                    )

                is CalendarSetupUiState.Loaded ->
                    CalendarSetupContent(
                        state = state,
                        onAddCalendar = onAddCalendar,
                        onEditCalendar = onEditCalendar,
                        onCalendarFormChanged = onCalendarFormChanged,
                        onCancelCalendarEdit = onCancelCalendarEdit,
                        onSubmitCalendar = onSubmitCalendar,
                    )
            }
        }
    }
}

/** The loaded screen: one vertically-scrolling column of the calendars section. A single scroll rather
 * than a [androidx.compose.foundation.lazy.LazyColumn] because the roster on this screen is a handful of
 * calendars, not an unbounded feed, and the create/edit form sits inside the same scroll above them. */
@Composable
private fun CalendarSetupContent(
    state: CalendarSetupUiState.Loaded,
    onAddCalendar: () -> Unit,
    onEditCalendar: (ConfiguredCalendar) -> Unit,
    onCalendarFormChanged: (CalendarForm) -> Unit,
    onCancelCalendarEdit: () -> Unit,
    onSubmitCalendar: (CalendarForm) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionLabel(text = stringResource(R.string.calendar_setup_section_calendars))
        val form = state.calendarForm
        if (form != null) {
            CalendarEditForm(
                form = form,
                busy = state.calendarFormBusy,
                onFormChanged = onCalendarFormChanged,
                onCancel = onCancelCalendarEdit,
                onSubmit = { onSubmitCalendar(form) },
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(onClick = onAddCalendar) {
                    Text(text = stringResource(R.string.calendar_setup_action_add_calendar))
                }
            }
            HorizontalDivider()
            if (state.calendars.isEmpty()) {
                Text(
                    text = stringResource(R.string.calendar_setup_calendars_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            } else {
                state.calendars.forEach { calendar ->
                    CalendarCard(calendar = calendar, onEdit = { onEditCalendar(calendar) })
                    HorizontalDivider()
                }
            }
        }
    }
}

/** One calendar: its name in bold, then a subdued `zone · опубликован/не опубликован` line — the word,
 * never a colour alone, the same rule [ServiceCard]'s own withdrawn marker follows. «Изменить» is the one
 * action: there is by design no delete-calendar endpoint to mirror (the port's own doc comment). */
@Composable
private fun CalendarCard(
    calendar: ConfiguredCalendar,
    onEdit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = calendar.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Text(
            text =
                stringResource(
                    R.string.calendar_setup_calendar_summary,
                    zoneDisplayLabel(calendar.timeZone),
                    stringResource(
                        if (calendar.published) {
                            R.string.calendar_setup_status_published
                        } else {
                            R.string.calendar_setup_status_unpublished
                        },
                    ),
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            TextButton(onClick = onEdit) {
                Text(text = stringResource(R.string.calendar_setup_action_edit))
            }
        }
    }
}

/**
 * The calendar create/edit form — over the roster, so there is never a second form on screen. On a create
 * it shows Название / Часовой пояс (the localized dropdown) / Опубликован; on an edit the timezone dropdown
 * is gone, because a calendar's zone is fixed at creation and the update request carries no field for it.
 */
@Composable
private fun CalendarEditForm(
    form: CalendarForm,
    busy: Boolean,
    onFormChanged: (CalendarForm) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = form.name,
            onValueChange = { onFormChanged(form.copy(name = it)) },
            label = { Text(text = stringResource(R.string.calendar_setup_field_name)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        if (form.isCreating) {
            TimeZoneDropdown(
                selectedZoneId = form.timeZoneId,
                enabled = !busy,
                onZoneSelected = { onFormChanged(form.copy(timeZoneId = it)) },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = form.published,
                onCheckedChange = { onFormChanged(form.copy(published = it)) },
                enabled = !busy,
            )
            Text(text = stringResource(R.string.calendar_setup_field_published), style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSubmit, enabled = !busy) {
                Text(
                    text =
                        stringResource(
                            if (form.isCreating) R.string.calendar_setup_action_create else R.string.calendar_setup_action_save,
                        ),
                )
            }
            TextButton(onClick = onCancel, enabled = !busy) {
                Text(text = stringResource(R.string.action_cancel))
            }
        }
    }
}

/**
 * `26-142`: the create-only timezone picker — a curated, localized dropdown, never free text, the author's
 * settled decision (`docs/design/26-139-*.md`) mirroring `ago-console`'s own `timeZoneOptions` since
 * `25-16`. The *stored* value is always an IANA zone id; only how a tenant picks it is a dropdown. Each
 * option is the curated zone's own localized name plus its offset read live from [java.time]
 * ([zoneDisplayLabel]), so a zone whose offset shifts stays correct with no code change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeZoneDropdown(
    selectedZoneId: String,
    enabled: Boolean,
    onZoneSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = zoneDisplayLabel(selectedZoneId),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(text = stringResource(R.string.calendar_setup_field_zone)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CURATED_TIME_ZONES.forEach { zone ->
                DropdownMenuItem(
                    text = { Text(text = zoneOptionLabel(zone)) },
                    onClick = {
                        expanded = false
                        onZoneSelected(zone.zoneId)
                    },
                )
            }
        }
    }
}

/** `26-142`: the eleven zones AGO serves, each with a localized-name string resource — the identical
 * curated set `ago-console`'s own `CURATED_TIME_ZONES` carries (`25-16`), bundled here rather than fetched
 * so the create form needs no extra endpoint. */
internal data class CuratedTimeZone(
    val zoneId: String,
    val labelRes: Int,
)

/** `Europe/Moscow`, the create form's own default — the same default `ago-console`'s calendar form opens
 * on. */
internal const val DEFAULT_CALENDAR_TIME_ZONE: String = "Europe/Moscow"

internal val CURATED_TIME_ZONES: List<CuratedTimeZone> =
    listOf(
        CuratedTimeZone("Europe/Kaliningrad", R.string.calendar_zone_europe_kaliningrad),
        CuratedTimeZone("Europe/Moscow", R.string.calendar_zone_europe_moscow),
        CuratedTimeZone("Europe/Samara", R.string.calendar_zone_europe_samara),
        CuratedTimeZone("Asia/Yekaterinburg", R.string.calendar_zone_asia_yekaterinburg),
        CuratedTimeZone("Asia/Omsk", R.string.calendar_zone_asia_omsk),
        CuratedTimeZone("Asia/Krasnoyarsk", R.string.calendar_zone_asia_krasnoyarsk),
        CuratedTimeZone("Asia/Irkutsk", R.string.calendar_zone_asia_irkutsk),
        CuratedTimeZone("Asia/Yakutsk", R.string.calendar_zone_asia_yakutsk),
        CuratedTimeZone("Asia/Vladivostok", R.string.calendar_zone_asia_vladivostok),
        CuratedTimeZone("Asia/Magadan", R.string.calendar_zone_asia_magadan),
        CuratedTimeZone("Asia/Kamchatka", R.string.calendar_zone_asia_kamchatka),
    )

/** One dropdown option: the curated zone's localized name and its live offset — `"Европа/Москва (+03:00)"`. */
@Composable
private fun zoneOptionLabel(zone: CuratedTimeZone): String =
    stringResource(R.string.calendar_setup_zone_label, stringResource(zone.labelRes), zoneOffsetLabel(zone.zoneId))

/** A card's or the field's zone label. A curated zone shows its localized name; a zone outside the eleven
 * (a legacy value the server returned) falls back to its raw IANA id — still labelled with its live
 * offset, never lost, the identical fallback `ago-console`'s own `timeZoneOptions` makes for an unlisted
 * saved zone. */
@Composable
private fun zoneDisplayLabel(zoneId: String): String {
    val curated = CURATED_TIME_ZONES.firstOrNull { it.zoneId == zoneId }
    val name = if (curated != null) stringResource(curated.labelRes) else zoneId
    return stringResource(R.string.calendar_setup_zone_label, name, zoneOffsetLabel(zoneId))
}

/** The zone's current UTC offset as `"+HH:MM"`, read live from [java.time] rather than stored — so a zone
 * whose offset changes stays correct without this file being touched. Guarded: an id that will not resolve
 * still renders a label, never throws through to the screen (the same posture the console's own
 * `zoneOffsetLabel` takes). */
private fun zoneOffsetLabel(zoneId: String): String =
    runCatching {
        val rules = ZoneId.of(zoneId).rules
        val offsetId = rules.getOffset(Instant.now()).id
        // `ZoneOffset.id` is "+HH:MM" for every real offset and the bare "Z" for UTC itself.
        if (offsetId == "Z") "+00:00" else offsetId
    }.getOrDefault("+00:00")
