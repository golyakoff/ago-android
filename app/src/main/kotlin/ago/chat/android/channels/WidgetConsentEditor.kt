package ago.chat.android.channels

import ago.chat.android.R
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TextButton
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
 * `26-217`/`W3` (`docs/design/tenant-widget-android.md` §6.3): «Согласие и запись» — the consent notice
 * (read-then-edit), the consent gate itself, the temporary unverified-phone relaxation, and the
 * attachments default. The third and last of the three group editors [WidgetConfigRoute] composes; it
 * reuses [WidgetConfigViewModel] exactly as [WidgetAppearanceEditor]/[WidgetBehaviourEditor] do — **no
 * new port, no new view model** (`26-193`/`W1` already declared both, and this screen edits only its own
 * five-field slice of the one shared [WidgetConfig]).
 *
 * **The full-DTO round-trip (§3), restated for this screen — and the one this screen carries the most
 * weight for.** [WidgetConfig.requireContactConsent] is `[JsonRequired]` on the wire precisely because an
 * omitted value silently disables a live gate (§1.1); this screen's Save always calls [onSave] with
 * `committed.copy(<its own five fields>)`, never a freshly-built [WidgetConfig], so
 * `requireContactConsent` — and the eleven fields Внешний вид/Поведение own — travel unchanged on every
 * save from here, the identical discipline the other two editors' own doc comments state for their own
 * slices. Each field is keyed on [committed] so a post-save re-entry re-seeds from the latest committed
 * value rather than resurrecting a stale draft.
 *
 * **The notice's read-then-edit shape (§6.3, mirroring the console's own `25-24`).** [noticeText]/
 * [noticeUrl] default to a read-only view of the *committed* values with an «Изменить» toggle; when
 * nothing is set yet the editor is the only thing shown, exactly as the console's own `noticeFormVisible`
 * treats `hasNotice === false`. The read view's own «показать полностью» toggle is independent of that
 * edit toggle — it only affects how much of the *already-saved* text this screen shows before an edit
 * starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetConsentEditor(
    committed: WidgetConfig,
    saving: Boolean,
    saveError: WidgetConfigSaveError?,
    savedTick: Int,
    onSave: (WidgetConfig) -> Unit,
    onBack: () -> Unit,
) {
    var requireContactConsent by rememberSaveable(committed) { mutableStateOf(committed.requireContactConsent) }
    var noticeTextInput by rememberSaveable(committed) { mutableStateOf(committed.noticeText.orEmpty()) }
    var noticeUrlInput by rememberSaveable(committed) { mutableStateOf(committed.noticeUrl.orEmpty()) }
    var acceptUnverifiedPhone by rememberSaveable(committed) { mutableStateOf(committed.acceptUnverifiedPhone) }
    var allowAttachmentUploadsByDefault by
        rememberSaveable(committed) { mutableStateOf(committed.allowAttachmentUploadsByDefault) }

    // `docs/design/tenant-widget-android.md` §6.3 / console `25-24`: a read view of what is *committed*
    // is the default once something is set; `noticeEditOpen` reveals the same editor `!hasNotice` would
    // show unconditionally. Keyed on `committed` so a post-save re-entry collapses back to the read view
    // the same way the console's own submit handler closes `noticeEditOpen` on success.
    val hasNotice = committed.noticeText != null || committed.noticeUrl != null
    var noticeEditOpen by rememberSaveable(committed) { mutableStateOf(false) }
    var noticeTextExpanded by rememberSaveable(committed) { mutableStateOf(false) }
    val noticeFormVisible = !hasNotice || noticeEditOpen

    // `docs/design/tenant-widget-android.md` §6.3: the console's own `isValidNoticeUrl` courtesy check -
    // an absolute `https://` link, since a notice URL is only ever opened in the visitor's own browser.
    // Blank is valid (it means "no link"), independent of `noticeText`.
    val noticeUrlValid = noticeUrlInput.isBlank() || isValidNoticeUrl(noticeUrlInput)

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
                    title = { Text(text = stringResource(R.string.widget_config_group_consent)) },
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
                NoticeSection(
                    hasNotice = hasNotice,
                    committedNoticeText = committed.noticeText,
                    committedNoticeUrl = committed.noticeUrl,
                    noticeTextExpanded = noticeTextExpanded,
                    onToggleExpanded = { noticeTextExpanded = !noticeTextExpanded },
                    noticeEditOpen = noticeEditOpen,
                    onToggleEditOpen = { noticeEditOpen = !noticeEditOpen },
                    noticeFormVisible = noticeFormVisible,
                    noticeTextInput = noticeTextInput,
                    onNoticeTextChange = { noticeTextInput = it },
                    noticeUrlInput = noticeUrlInput,
                    onNoticeUrlChange = { noticeUrlInput = it },
                    noticeUrlValid = noticeUrlValid,
                    enabled = !saving,
                )

                ToggleWithCaption(
                    label = stringResource(R.string.widget_config_field_require_consent_label),
                    caption = stringResource(R.string.widget_config_field_require_consent_caption),
                    checked = requireContactConsent,
                    enabled = !saving,
                    onCheckedChange = { requireContactConsent = it },
                )

                ToggleWithCaption(
                    label = stringResource(R.string.widget_config_field_accept_unverified_phone_label),
                    caption = stringResource(R.string.widget_config_field_accept_unverified_phone_caption),
                    checked = acceptUnverifiedPhone,
                    enabled = !saving,
                    onCheckedChange = { acceptUnverifiedPhone = it },
                )

                ToggleWithCaption(
                    label = stringResource(R.string.widget_config_field_allow_attachment_uploads_label),
                    caption = stringResource(R.string.widget_config_field_allow_attachment_uploads_caption),
                    checked = allowAttachmentUploadsByDefault,
                    enabled = !saving,
                    onCheckedChange = { allowAttachmentUploadsByDefault = it },
                )

                saveError?.let { error -> WidgetConfigErrorBanner(error = error, modifier = Modifier.fillMaxWidth()) }

                Button(
                    onClick = {
                        onSave(
                            committed.copy(
                                noticeText = noticeTextInput.trim().ifBlank { null },
                                noticeUrl = noticeUrlInput.trim().ifBlank { null },
                                requireContactConsent = requireContactConsent,
                                acceptUnverifiedPhone = acceptUnverifiedPhone,
                                allowAttachmentUploadsByDefault = allowAttachmentUploadsByDefault,
                            ),
                        )
                    },
                    enabled = noticeUrlValid && !saving,
                ) {
                    Text(text = stringResource(R.string.widget_config_action_save))
                }
            }
        }
    }
}

/** The notice's own read-then-edit block (§6.3) — [ToggleWithCaption] does not fit here since this is not
 * a single switch, but a read view over two possibly-absent strings plus an editor toggle. Kept private
 * to this file; the shape is specific to the one field pair that needs it. */
@Suppress("LongParameterList")
@Composable
private fun NoticeSection(
    hasNotice: Boolean,
    committedNoticeText: String?,
    committedNoticeUrl: String?,
    noticeTextExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    noticeEditOpen: Boolean,
    onToggleEditOpen: () -> Unit,
    noticeFormVisible: Boolean,
    noticeTextInput: String,
    onNoticeTextChange: (String) -> Unit,
    noticeUrlInput: String,
    onNoticeUrlChange: (String) -> Unit,
    noticeUrlValid: Boolean,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.widget_config_field_notice_section_label), style = MaterialTheme.typography.labelLarge)

        if (hasNotice) {
            val preview = previewNoticeText(committedNoticeText.orEmpty())
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = stringResource(R.string.widget_config_field_notice_current_label), style = MaterialTheme.typography.bodySmall)
                Text(
                    text = if (noticeTextExpanded) committedNoticeText.orEmpty() else preview.visible,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (preview.truncated) {
                    TextButton(onClick = onToggleExpanded) {
                        Text(
                            text =
                                stringResource(
                                    if (noticeTextExpanded) {
                                        R.string.widget_config_field_notice_show_less
                                    } else {
                                        R.string.widget_config_field_notice_show_fully
                                    },
                                ),
                        )
                    }
                }
                if (committedNoticeUrl != null) {
                    Text(
                        text = stringResource(R.string.widget_config_field_notice_url_current_label),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(text = committedNoticeUrl, style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(onClick = onToggleEditOpen) {
                Text(
                    text =
                        stringResource(
                            if (noticeEditOpen) R.string.action_cancel else R.string.widget_config_field_notice_edit,
                        ),
                )
            }
        } else {
            Text(text = stringResource(R.string.widget_config_field_notice_not_set), style = MaterialTheme.typography.bodyMedium)
        }

        if (noticeFormVisible) {
            OutlinedTextField(
                value = noticeTextInput,
                onValueChange = onNoticeTextChange,
                label = { Text(text = stringResource(R.string.widget_config_field_notice_text_label)) },
                placeholder = { Text(text = stringResource(R.string.widget_config_field_notice_text_placeholder)) },
                supportingText = { Text(text = stringResource(R.string.widget_config_field_notice_text_supporting)) },
                minLines = 3,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = noticeUrlInput,
                onValueChange = onNoticeUrlChange,
                label = { Text(text = stringResource(R.string.widget_config_field_notice_url_label)) },
                placeholder = { Text(text = "https://example.com/privacy") },
                supportingText = {
                    Text(
                        text =
                            stringResource(
                                if (noticeUrlValid) {
                                    R.string.widget_config_field_notice_url_supporting
                                } else {
                                    R.string.widget_config_field_notice_url_invalid
                                },
                            ),
                    )
                },
                isError = !noticeUrlValid,
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A [Switch] with its own label to the left, plus a one-line caption underneath — the identical shape
 * [WidgetBehaviourEditor]'s own private `ToggleWithCaption` establishes, restated here (file-private, so
 * no cross-file visibility change to a file this slice does not otherwise touch) rather than shared,
 * since only these two files need it. */
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

/** The truncated-preview shape the console's own `truncateToLines`/`NOTICE_TEXT_PREVIEW_LINES` establish
 * for the notice's read view — the first ten lines, plus whether there was more to cut. */
private data class NoticeTextPreview(
    val visible: String,
    val truncated: Boolean,
)

private const val NOTICE_TEXT_PREVIEW_LINES: Int = 10

private fun previewNoticeText(text: String): NoticeTextPreview {
    val lines = text.lines()
    return if (lines.size <= NOTICE_TEXT_PREVIEW_LINES) {
        NoticeTextPreview(visible = text, truncated = false)
    } else {
        NoticeTextPreview(visible = lines.take(NOTICE_TEXT_PREVIEW_LINES).joinToString("\n"), truncated = true)
    }
}

/** The console's own `isValidNoticeUrl` courtesy check (`widgetConfigValidation.ts`) — an absolute
 * `https://` URL; a notice link is only ever opened in the visitor's own browser, never fetched by the
 * server, so this is a plain prefix-plus-non-empty check rather than the wider SSRF-aware validator a
 * server-fetched URL (a webhook) would need. UX-only; `WidgetConfig.InvalidNoticeUrl` is the real gate. */
private fun isValidNoticeUrl(value: String): Boolean {
    val trimmed = value.trim()
    return trimmed.startsWith("https://") && trimmed.length > "https://".length
}
