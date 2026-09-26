package ago.chat.android.channels

import ago.chat.android.R
import ago.chat.android.core.domain.widgetconfig.ChannelSwitcherIconSize
import ago.chat.android.core.domain.widgetconfig.ChannelSwitcherPlacement
import ago.chat.android.core.domain.widgetconfig.WidgetConfig
import ago.chat.android.core.domain.widgetconfig.WidgetLocale
import ago.chat.android.core.domain.widgetconfig.WidgetPosition
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * `26-193` (`docs/design/tenant-widget-android.md` §6.1/§7): «Внешний вид» — colour (hex + live swatch),
 * launcher corner, widget language, panel title, and the channel-switcher placement/icon-size pair. The
 * only wired group editor in `W1`; `W2`/`W3` add Поведение и приветствие/Согласие и запись as siblings of
 * this file, sharing the same [WidgetConfigViewModel] via [WidgetConfigRoute].
 *
 * **The full-DTO round-trip (§3).** The six `rememberSaveable` fields below hold only this screen's own
 * slice; the Save [Button]'s `onClick` always calls [onSave] with `committed.copy(<those six fields>)`,
 * never a freshly-built [WidgetConfig] — so the other ten fields this screen never shows travel unchanged
 * on every save. Each field is keyed on [committed] (`rememberSaveable(committed) { … }`) so a post-save
 * re-entry, or a config change arriving mid-edit, re-seeds from the latest committed value rather than
 * resurrecting a stale one (`docs/design/tenant-widget-android.md` §5.3's own closing note).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetAppearanceEditor(
    committed: WidgetConfig,
    saving: Boolean,
    saveError: WidgetConfigSaveError?,
    savedTick: Int,
    onSave: (WidgetConfig) -> Unit,
    onBack: () -> Unit,
) {
    var colorHexInput by rememberSaveable(committed) { mutableStateOf(committed.primaryColorHex.orEmpty()) }
    var position by rememberSaveable(committed) { mutableStateOf(committed.position) }
    var locale by rememberSaveable(committed) { mutableStateOf(committed.locale) }
    var panelTitleInput by rememberSaveable(committed) { mutableStateOf(committed.panelTitle.orEmpty()) }
    var channelSwitcherPlacement by rememberSaveable(committed) { mutableStateOf(committed.channelSwitcherPlacement) }
    var channelSwitcherIconSize by rememberSaveable(committed) { mutableStateOf(committed.channelSwitcherIconSize) }

    val colorHexValid = colorHexInput.isBlank() || parseHexColorOrNull(colorHexInput) != null

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
                    title = { Text(text = stringResource(R.string.widget_config_group_appearance)) },
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
                ColorField(
                    value = colorHexInput,
                    valid = colorHexValid,
                    onValueChange = { colorHexInput = it },
                )

                LabeledField(stringResource(R.string.widget_config_field_position_label)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        WidgetPosition.entries.forEachIndexed { index, entry ->
                            SegmentedButton(
                                selected = entry == position,
                                onClick = { position = entry },
                                shape = SegmentedButtonDefaults.itemShape(index, WidgetPosition.entries.size),
                                icon = {},
                                label = { Text(text = positionLabel(entry)) },
                            )
                        }
                    }
                }

                LabeledField(stringResource(R.string.widget_config_field_locale_label)) {
                    EnumDropdown(
                        selected = locale,
                        options = WidgetLocale.entries,
                        label = { localeEndonym(it) },
                        onSelected = { locale = it },
                    )
                }

                OutlinedTextField(
                    value = panelTitleInput,
                    onValueChange = { if (it.length <= PANEL_TITLE_MAX_LENGTH) panelTitleInput = it },
                    label = { Text(text = stringResource(R.string.widget_config_field_panel_title_label)) },
                    supportingText = {
                        Text(
                            text =
                                stringResource(
                                    R.string.widget_config_field_panel_title_supporting,
                                    panelTitleInput.length,
                                    PANEL_TITLE_MAX_LENGTH,
                                ),
                        )
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )

                LabeledField(stringResource(R.string.widget_config_field_channel_switcher_placement_label)) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ChannelSwitcherPlacement.entries.forEachIndexed { index, entry ->
                            SegmentedButton(
                                selected = entry == channelSwitcherPlacement,
                                onClick = { channelSwitcherPlacement = entry },
                                shape = SegmentedButtonDefaults.itemShape(index, ChannelSwitcherPlacement.entries.size),
                                icon = {},
                                label = { Text(text = channelSwitcherPlacementLabel(entry)) },
                            )
                        }
                    }
                }

                // Shown only while the switcher actually sits below the launcher (mirroring the console's
                // own conditional render) - the field's current value is still sent regardless
                // (`WidgetConfig.channelSwitcherIconSize`'s own doc comment), so a hidden setting never
                // resets by being invisible.
                if (channelSwitcherPlacement == ChannelSwitcherPlacement.BelowLauncher) {
                    LabeledField(stringResource(R.string.widget_config_field_icon_size_label)) {
                        EnumDropdown(
                            selected = channelSwitcherIconSize,
                            options = ChannelSwitcherIconSize.entries,
                            label = { channelSwitcherIconSizeLabel(it) },
                            onSelected = { channelSwitcherIconSize = it },
                        )
                    }
                }

                saveError?.let { error -> WidgetConfigErrorBanner(error = error, modifier = Modifier.fillMaxWidth()) }

                Button(
                    onClick = {
                        onSave(
                            committed.copy(
                                primaryColorHex = colorHexInput.trim().ifBlank { null },
                                position = position,
                                locale = locale,
                                panelTitle = panelTitleInput.ifBlank { null },
                                channelSwitcherPlacement = channelSwitcherPlacement,
                                channelSwitcherIconSize = channelSwitcherIconSize,
                            ),
                        )
                    },
                    enabled = colorHexValid && !saving,
                ) {
                    Text(text = stringResource(R.string.widget_config_action_save))
                }
            }
        }
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

/** The hex field plus its live colour swatch (`docs/design/tenant-widget-android.md` §7 decision 1) — the
 * console's own `.ago-widget-swatch` ported verbatim: the parsed colour when [value] is valid, the
 * widget's own built-in default otherwise. */
@Composable
private fun ColorField(
    value: String,
    valid: Boolean,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(text = stringResource(R.string.widget_config_field_color_label)) },
            placeholder = { Text(text = DEFAULT_WIDGET_COLOR_HEX) },
            singleLine = true,
            isError = !valid,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            trailingIcon = {
                Box(
                    modifier =
                        Modifier
                            .padding(end = 12.dp)
                            .size(24.dp)
                            .background(color = parseHexColorOrNull(value) ?: DEFAULT_WIDGET_COLOR, shape = CircleShape)
                            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = CircleShape),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!valid) {
            Text(
                text = stringResource(R.string.widget_config_field_color_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** A read-only [ExposedDropdownMenuBox] over a fixed, small enum — the identical shape
 * [ago.chat.android.bookings.CalendarSetupBody]'s own `TimeZoneDropdown` establishes, generalised over
 * any enum's [options] since this screen needs the same shape twice ([WidgetLocale],
 * [ChannelSwitcherIconSize]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    selected: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label(selected),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = label(option)) },
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
private fun positionLabel(position: WidgetPosition): String =
    stringResource(
        when (position) {
            WidgetPosition.BottomRight -> R.string.widget_config_position_bottom_right
            WidgetPosition.BottomLeft -> R.string.widget_config_position_bottom_left
        },
    )

@Composable
private fun channelSwitcherPlacementLabel(placement: ChannelSwitcherPlacement): String =
    stringResource(
        when (placement) {
            ChannelSwitcherPlacement.AboveComposer -> R.string.widget_config_channel_switcher_above
            ChannelSwitcherPlacement.BelowLauncher -> R.string.widget_config_channel_switcher_below
        },
    )

@Composable
private fun channelSwitcherIconSizeLabel(size: ChannelSwitcherIconSize): String =
    stringResource(
        when (size) {
            ChannelSwitcherIconSize.Large -> R.string.widget_config_icon_size_large
            ChannelSwitcherIconSize.Medium -> R.string.widget_config_icon_size_medium
            ChannelSwitcherIconSize.Small -> R.string.widget_config_icon_size_small
        },
    )

/** [WidgetLocale]'s own endonym — `"English"`/`"Русский"`, **never translated**
 * ([ago.chat.android.core.domain.widgetconfig.WidgetLocale]'s own doc comment states why), so this is a
 * plain hard-coded map rather than a string resource pair. */
private fun localeEndonym(locale: WidgetLocale): String =
    when (locale) {
        WidgetLocale.En -> "English"
        WidgetLocale.Ru -> "Русский"
    }

/** The console's own client-side courtesy check (`isValidHexColor`) — a 6-digit hex colour, `#` required.
 * Blank is valid (it means "use the widget's own default", not "invalid"); `null` here means genuinely
 * unparsable, distinct from blank. */
private fun parseHexColorOrNull(hex: String): Color? {
    val trimmed = hex.trim()
    if (!HEX_COLOR_REGEX.matches(trimmed)) return null
    return runCatching {
        val rgb = trimmed.removePrefix("#").toLong(16)
        Color(red = (rgb shr 16 and 0xFF) / 255f, green = (rgb shr 8 and 0xFF) / 255f, blue = (rgb and 0xFF) / 255f)
    }.getOrNull()
}

private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

/** The widget's own built-in default accent (`docs/design/tenant-widget-android.md` §7 decision 1's own
 * "the parsed colour or the `#2f6fed` default" statement, read from `WidgetConfigPage.tsx`). */
private const val DEFAULT_WIDGET_COLOR_HEX: String = "#2F6FED"
private val DEFAULT_WIDGET_COLOR = Color(0xFF2F6FED)

/** UX-only courtesy counter (`docs/design/tenant-widget-android.md` §6.1) - the server is the
 * authoritative gate (`WidgetConfig.InvalidPanelTitle`), this only stops an obviously-too-long draft
 * before a round trip. */
private const val PANEL_TITLE_MAX_LENGTH: Int = 300
