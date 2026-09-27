package ago.chat.android.channels

import ago.chat.android.R
import ago.chat.android.core.domain.widgetconfig.WidgetAutoOpenDelay
import ago.chat.android.core.domain.widgetconfig.WidgetConfig
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * `26-216`/`W2` (`docs/design/tenant-widget-android.md` §6.2): «Поведение и приветствие» — attract-
 * attention, auto-open (enabled/delay/greeting), and the contact-capture confirmation line. The second
 * of the three group editors [WidgetConfigRoute] composes; it reuses [WidgetConfigViewModel] exactly as
 * [WidgetAppearanceEditor] does — **no new port, no new view model** (`26-193`/`W1` already declared
 * both, and this screen edits only its own five-field slice of the one shared [WidgetConfig]).
 *
 * **The full-DTO round-trip (§3), restated for this screen.** The five `rememberSaveable` fields below
 * hold only this screen's own slice; Save always calls [onSave] with `committed.copy(<those five
 * fields>)`, never a freshly-built [WidgetConfig] — so the eleven fields Внешний вид/Согласие own travel
 * unchanged on every save from here, the identical discipline [WidgetAppearanceEditor]'s own doc comment
 * states for its six. Each field is keyed on [committed] so a post-save re-entry re-seeds from the latest
 * committed value rather than resurrecting a stale draft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetBehaviourEditor(
    committed: WidgetConfig,
    saving: Boolean,
    saveError: WidgetConfigSaveError?,
    savedTick: Int,
    onSave: (WidgetConfig) -> Unit,
    onBack: () -> Unit,
) {
    var attractAttention by rememberSaveable(committed) { mutableStateOf(committed.attractAttention) }
    var autoOpenEnabled by rememberSaveable(committed) { mutableStateOf(committed.autoOpenEnabled) }
    var autoOpenDelay by rememberSaveable(committed) { mutableStateOf(committed.autoOpenDelay) }
    var autoOpenGreetingInput by rememberSaveable(committed) { mutableStateOf(committed.autoOpenGreetingText.orEmpty()) }
    var contactCaptureConfirmationInput by
        rememberSaveable(committed) { mutableStateOf(committed.contactCaptureConfirmationText.orEmpty()) }

    // `docs/design/tenant-widget-android.md` §6.2, mirroring the console's own `WidgetConfigPage` submit
    // check: a blank greeting is fine while auto-open is off (it simply has nothing to show), but a real
    // gate once it is on — the server has no default sentence to fall back to
    // (`WidgetConfig.InvalidAutoOpenGreetingText`). UX-only; a false "looks fine" here just means the
    // server refuses the save instead, surfaced the same way as any other [WidgetConfigSaveError].
    val greetingRequired = autoOpenEnabled && autoOpenGreetingInput.isBlank()

    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.widget_config_saved)
    LaunchedEffect(savedTick) {
        if (savedTick > 0) snackbarHostState.showSnackbar(savedMessage)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.widget_config_group_behaviour)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ToggleWithCaption(
                    label = stringResource(R.string.widget_config_field_attract_attention_label),
                    caption = stringResource(R.string.widget_config_field_attract_attention_caption),
                    checked = attractAttention,
                    enabled = !saving,
                    onCheckedChange = { attractAttention = it },
                )

                ToggleWithCaption(
                    label = stringResource(R.string.widget_config_field_auto_open_label),
                    caption = stringResource(R.string.widget_config_field_auto_open_caption),
                    checked = autoOpenEnabled,
                    enabled = !saving,
                    onCheckedChange = { autoOpenEnabled = it },
                )

                // `docs/design/tenant-widget-android.md` §6.2: always visible, no branching on
                // `autoOpenEnabled` — the console does not hide it either, and the value round-trips
                // unchanged (`WidgetAppearanceEditor`'s own `channelSwitcherIconSize` states the same
                // "a hidden setting never resets by being invisible" reasoning; here it is simply never
                // hidden at all).
                LabeledField(stringResource(R.string.widget_config_field_auto_open_delay_label)) {
                    AutoOpenDelayDropdown(selected = autoOpenDelay, onSelected = { autoOpenDelay = it })
                }

                OutlinedTextField(
                    value = autoOpenGreetingInput,
                    onValueChange = { autoOpenGreetingInput = it },
                    label = { Text(text = stringResource(R.string.widget_config_field_auto_open_greeting_label)) },
                    placeholder = { Text(text = stringResource(R.string.widget_config_field_auto_open_greeting_placeholder)) },
                    isError = greetingRequired,
                    supportingText = {
                        if (greetingRequired) {
                            Text(text = stringResource(R.string.widget_config_field_auto_open_greeting_required))
                        }
                    },
                    minLines = 2,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = contactCaptureConfirmationInput,
                    onValueChange = { contactCaptureConfirmationInput = it },
                    label = { Text(text = stringResource(R.string.widget_config_field_contact_capture_confirmation_label)) },
                    placeholder = {
                        Text(text = stringResource(R.string.widget_config_field_contact_capture_confirmation_placeholder))
                    },
                    supportingText = {
                        Text(text = stringResource(R.string.widget_config_field_contact_capture_confirmation_supporting))
                    },
                    minLines = 2,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )

                saveError?.let { error -> WidgetConfigErrorBanner(error = error, modifier = Modifier.fillMaxWidth()) }

                Button(
                    onClick = {
                        onSave(
                            committed.copy(
                                attractAttention = attractAttention,
                                autoOpenEnabled = autoOpenEnabled,
                                autoOpenDelay = autoOpenDelay,
                                autoOpenGreetingText = autoOpenGreetingInput.trim().ifBlank { null },
                                contactCaptureConfirmationText = contactCaptureConfirmationInput.trim().ifBlank { null },
                            ),
                        )
                    },
                    enabled = !greetingRequired && !saving,
                ) {
                    Text(text = stringResource(R.string.widget_config_action_save))
                }
            }
        }
    }
}

/** A [Switch] with its own label to the left, plus a one-line caption underneath — the identical
 * "checkbox row, then a sibling meta line" shape [OfflineAutoReplyScreen]'s own enabled toggle already
 * establishes for `attractAttention`'s console sibling. */
@Composable
private fun ToggleWithCaption(
    label: String,
    caption: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        Text(text = caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LabeledField(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

/** A read-only [ExposedDropdownMenuBox] over the six fixed [WidgetAutoOpenDelay] values — the identical
 * shape [WidgetAppearanceEditor]'s own private `EnumDropdown` already establishes for its two enums,
 * restated here (file-private, so no cross-file visibility change to a file this slice does not
 * otherwise touch) rather than shared, since this file needs the shape exactly once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoOpenDelayDropdown(
    selected: WidgetAutoOpenDelay,
    onSelected: (WidgetAutoOpenDelay) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = autoOpenDelayLabel(selected),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            WidgetAutoOpenDelay.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = autoOpenDelayLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun autoOpenDelayLabel(delay: WidgetAutoOpenDelay): String =
    stringResource(
        when (delay) {
            WidgetAutoOpenDelay.Seconds15 -> R.string.widget_config_auto_open_delay_15
            WidgetAutoOpenDelay.Seconds30 -> R.string.widget_config_auto_open_delay_30
            WidgetAutoOpenDelay.Seconds45 -> R.string.widget_config_auto_open_delay_45
            WidgetAutoOpenDelay.Seconds60 -> R.string.widget_config_auto_open_delay_60
            WidgetAutoOpenDelay.Seconds90 -> R.string.widget_config_auto_open_delay_90
            WidgetAutoOpenDelay.Seconds120 -> R.string.widget_config_auto_open_delay_120
        },
    )
