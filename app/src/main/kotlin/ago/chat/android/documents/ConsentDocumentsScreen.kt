package ago.chat.android.documents

import ago.chat.android.R
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.core.domain.consent.ConsentAcceptance
import ago.chat.android.core.domain.consent.ConsentDocumentBounds
import ago.chat.android.core.domain.consent.ConsentDocumentSummary
import ago.chat.android.core.domain.consent.ConsentPurpose
import ago.chat.android.core.domain.consent.ConsentVersion
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-226` (`docs/design/tenant-consent-android.md` §3): Администрирование → «Документы согласий» -
 * two purposes' consent documents (Contact, Marketing), each with its own binding-status badge,
 * version list and per-version "кто принял" expander, plus a full-screen publish editor. Obtains its
 * own [ConsentDocumentsViewModel] via [hiltViewModel] - the identical wiring
 * [ago.chat.android.automation.CannedResponsesRoute] already establishes for a drill-in
 * [ago.chat.android.shell.MoreScreen] composes only for an operator holding `site:configure`.
 */
@Composable
internal fun ConsentDocumentsRoute(
    onBack: () -> Unit,
    viewModel: ConsentDocumentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ConsentDocumentsScreen(
        state = state,
        onRetry = viewModel::refresh,
        onExpandAcceptances = viewModel::loadAcceptances,
        onRetryAcceptances = viewModel::retryAcceptances,
        onPublish = viewModel::publish,
        onBack = onBack,
    )
}

/**
 * The stateless half - an overview, and a full-screen editor, the identical
 * [ago.chat.android.automation.CannedResponsesScreen] shape: **which purpose's editor is open is
 * transient composition state**, via [remember], never a [ConsentDocumentsUiState] arm. A successful
 * publish bumps [ConsentDocumentsUiState.Loaded.savedTick], this screen's own signal to close the
 * editor and return to the overview; a refused/failed/conflicted publish never bumps it, so the editor
 * stays open so the operator can retry or back out.
 *
 * There is deliberately no [ago.chat.android.documents.ConsentPublishEditorScreen] reader sibling here
 * - reading a version's own published text on the phone is a separate item (this ticket's own scope
 * cut, `tenant-consent-android.md` §1.2/§3.3), so a version's row offers no "Открыть" action.
 */
@Composable
internal fun ConsentDocumentsScreen(
    state: ConsentDocumentsUiState,
    onRetry: () -> Unit,
    onExpandAcceptances: (AcceptanceKey) -> Unit,
    onRetryAcceptances: (AcceptanceKey) -> Unit,
    onPublish: (ConsentPurpose, String, String) -> Unit,
    onBack: () -> Unit,
) {
    var editingPurpose by remember { mutableStateOf<ConsentPurpose?>(null) }

    val savedTick = (state as? ConsentDocumentsUiState.Loaded)?.savedTick ?: 0
    LaunchedEffect(savedTick) {
        if (savedTick > 0) editingPurpose = null
    }

    val purpose = editingPurpose
    if (purpose != null) {
        ConsentPublishEditorScreen(
            purposeTitle = stringResource(purposeTitleRes(purpose)),
            publishing = (state as? ConsentDocumentsUiState.Loaded)?.publishing ?: false,
            error = (state as? ConsentDocumentsUiState.Loaded)?.publishError,
            onPublish = { title, body -> onPublish(purpose, title, body) },
            onBack = { editingPurpose = null },
        )
    } else {
        ConsentDocumentsOverviewScreen(
            state = state,
            onRetry = onRetry,
            onExpandAcceptances = onExpandAcceptances,
            onRetryAcceptances = onRetryAcceptances,
            onPublishRequest = { editingPurpose = it },
            onBack = onBack,
        )
    }
}

/** The overview - Route/Screen split, back arrow, no
 * [ago.chat.android.ui.components.AccountAvatarAction] (a drill-in), the identical shape
 * [ago.chat.android.automation.CannedResponsesScreen]'s own list screen already establishes. The
 * «Версия опубликована» snackbar lives here, not on the editor - shown only once the editor has
 * already closed and this screen is what the operator sees; the "id copied" snackbar (from a
 * long-pressed acceptance row, several levels down) rides the same host rather than each panel owning
 * its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsentDocumentsOverviewScreen(
    state: ConsentDocumentsUiState,
    onRetry: () -> Unit,
    onExpandAcceptances: (AcceptanceKey) -> Unit,
    onRetryAcceptances: (AcceptanceKey) -> Unit,
    onPublishRequest: (ConsentPurpose) -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val savedMessage = stringResource(R.string.consent_publish_saved_snackbar)
    val copiedMessage = stringResource(R.string.consent_acceptances_id_copied)
    val savedTick = (state as? ConsentDocumentsUiState.Loaded)?.savedTick ?: 0
    LaunchedEffect(savedTick) {
        if (savedTick > 0) snackbarHostState.showSnackbar(savedMessage)
    }
    val onCopied: () -> Unit = { scope.launch { snackbarHostState.showSnackbar(copiedMessage) } }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.more_administration_documents_row)) },
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
                    ConsentDocumentsUiState.Loading -> LoadingBody()
                    is ConsentDocumentsUiState.Failed -> ConsentDocumentsFailedBody(reason = state.reason, onRetry = onRetry)
                    is ConsentDocumentsUiState.Loaded ->
                        ConsentDocumentsLoadedBody(
                            state = state,
                            onExpandAcceptances = onExpandAcceptances,
                            onRetryAcceptances = onRetryAcceptances,
                            onPublishRequest = onPublishRequest,
                            onCopied = onCopied,
                        )
                }
            }
        }
    }
}

/** The read itself failed - the identical "title, [networkFailureText], retry" shape
 * [ago.chat.android.automation.CannedResponsesScreen]'s own private failed body already establishes,
 * restated here since neither file imports composables from the other. */
@Composable
private fun ConsentDocumentsFailedBody(
    reason: NetworkFailure,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = networkFailureText(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

/** Both purposes, Contact then Marketing, each its own [Card] (the mockup's `.card`,
 * `tenant-consent-android.md` §3.1). */
@Composable
private fun ConsentDocumentsLoadedBody(
    state: ConsentDocumentsUiState.Loaded,
    onExpandAcceptances: (AcceptanceKey) -> Unit,
    onRetryAcceptances: (AcceptanceKey) -> Unit,
    onPublishRequest: (ConsentPurpose) -> Unit,
    onCopied: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            ConsentPurposePanel(
                purpose = ConsentPurpose.Contact,
                titleRes = R.string.consent_contact_panel_title,
                summary = state.overview.contact,
                badgeText =
                    stringResource(
                        if (state.overview.contactConsentRequired) {
                            R.string.consent_contact_required_badge
                        } else {
                            R.string.consent_contact_not_required_badge
                        },
                    ),
                badgeTone = if (state.overview.contactConsentRequired) BadgeTone.Info else BadgeTone.Danger,
                acceptances = state.acceptances,
                onExpandAcceptances = onExpandAcceptances,
                onRetryAcceptances = onRetryAcceptances,
                onPublishRequest = onPublishRequest,
                onCopied = onCopied,
            )
        }
        item {
            ConsentPurposePanel(
                purpose = ConsentPurpose.Marketing,
                titleRes = R.string.consent_marketing_panel_title,
                summary = state.overview.marketing,
                badgeText = stringResource(R.string.consent_marketing_badge),
                badgeTone = BadgeTone.Info,
                acceptances = state.acceptances,
                onExpandAcceptances = onExpandAcceptances,
                onRetryAcceptances = onRetryAcceptances,
                onPublishRequest = onPublishRequest,
                onCopied = onCopied,
            )
        }
    }
}

/** One purpose's whole card: title, badge, current version + its own acceptances expander, an
 * expandable older-versions list (each with its own expander), and the publish action - drawn always,
 * even when nothing has been published yet (`tenant-consent-android.md` §4.3's own first edge case). */
@Composable
private fun ConsentPurposePanel(
    purpose: ConsentPurpose,
    titleRes: Int,
    summary: ConsentDocumentSummary,
    badgeText: String,
    badgeTone: BadgeTone,
    acceptances: Map<AcceptanceKey, AcceptancesUiState>,
    onExpandAcceptances: (AcceptanceKey) -> Unit,
    onRetryAcceptances: (AcceptanceKey) -> Unit,
    onPublishRequest: (ConsentPurpose) -> Unit,
    onCopied: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ConsentBadge(text = badgeText, tone = badgeTone)

            val current = summary.versions.firstOrNull()
            if (current == null) {
                Text(
                    text = stringResource(R.string.consent_no_versions_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ConsentVersionRow(version = current, isCurrent = true)
                ConsentAcceptancesExpander(
                    acceptanceKey = AcceptanceKey(purpose, current.version),
                    entryState = acceptances[AcceptanceKey(purpose, current.version)],
                    onExpand = onExpandAcceptances,
                    onRetry = onRetryAcceptances,
                    onCopied = onCopied,
                )
            }

            val olderVersions = summary.versions.drop(1)
            if (olderVersions.isNotEmpty()) {
                ConsentOlderVersionsSection(
                    purpose = purpose,
                    olderVersions = olderVersions,
                    acceptances = acceptances,
                    onExpandAcceptances = onExpandAcceptances,
                    onRetryAcceptances = onRetryAcceptances,
                    onCopied = onCopied,
                )
            }

            OutlinedButton(onClick = { onPublishRequest(purpose) }, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.consent_publish_action))
            }
        }
    }
}

/** «Предыдущие версии (N)» - collapsed by default, transient composition state
 * (`tenant-consent-android.md` §3.1), the identical [ExpandChevron] idiom
 * [ago.chat.android.shell.SettingsScreen]'s own expandable row already establishes. */
@Composable
private fun ConsentOlderVersionsSection(
    purpose: ConsentPurpose,
    olderVersions: List<ConsentVersion>,
    acceptances: Map<AcceptanceKey, AcceptancesUiState>,
    onExpandAcceptances: (AcceptanceKey) -> Unit,
    onRetryAcceptances: (AcceptanceKey) -> Unit,
    onCopied: () -> Unit,
) {
    var expanded by remember(purpose) { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.consent_older_versions_heading, olderVersions.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            ExpandChevron(expanded = expanded)
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                olderVersions.forEach { version ->
                    HorizontalDivider()
                    ConsentVersionRow(version = version, isCurrent = false)
                    ConsentAcceptancesExpander(
                        acceptanceKey = AcceptanceKey(purpose, version.version),
                        entryState = acceptances[AcceptanceKey(purpose, version.version)],
                        onExpand = onExpandAcceptances,
                        onRetry = onRetryAcceptances,
                        onCopied = onCopied,
                    )
                }
            }
        }
    }
}

/** One version's own title/version/date - no "Открыть" action (this ticket's own scope cut, this
 * file's own top-of-file doc comment). */
@Composable
private fun ConsentVersionRow(
    version: ConsentVersion,
    isCurrent: Boolean,
) {
    Column {
        if (isCurrent) {
            Text(
                text = stringResource(R.string.consent_current_version_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(text = version.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            text = "${version.version} · ${consentVersionDate(version.publishedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One version's own "кто принял" - fetched lazily on first expand
 * ([ConsentDocumentsViewModel.loadAcceptances]'s own doc comment), collapsed by default. */
@Composable
private fun ConsentAcceptancesExpander(
    acceptanceKey: AcceptanceKey,
    entryState: AcceptancesUiState?,
    onExpand: (AcceptanceKey) -> Unit,
    onRetry: (AcceptanceKey) -> Unit,
    onCopied: () -> Unit,
) {
    var expanded by remember(acceptanceKey) { mutableStateOf(false) }
    Column {
        Row(
            modifier =
                Modifier.fillMaxWidth().clickable {
                    val wasExpanded = expanded
                    expanded = !wasExpanded
                    if (!wasExpanded && entryState == null) onExpand(acceptanceKey)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    stringResource(
                        if (expanded) R.string.consent_acceptances_toggle_hide else R.string.consent_acceptances_toggle_show,
                    ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            ExpandChevron(expanded = expanded)
        }
        if (expanded) {
            Box(modifier = Modifier.padding(top = 8.dp)) {
                when (entryState) {
                    null, AcceptancesUiState.Loading -> ConsentSmallLoadingRow()
                    is AcceptancesUiState.Failed ->
                        ConsentAcceptancesFailedRow(reason = entryState.reason, onRetry = { onRetry(acceptanceKey) })

                    is AcceptancesUiState.Loaded ->
                        ConsentAcceptancesTable(acceptances = entryState.acceptances, onCopied = onCopied)
                }
            }
        }
    }
}

@Composable
private fun ConsentSmallLoadingRow() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ConsentAcceptancesFailedRow(
    reason: NetworkFailure,
    onRetry: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = networkFailureText(reason),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text(text = stringResource(R.string.action_retry)) }
    }
}

/** The «кто принял» table itself - two columns, phone-narrowed from the console's three
 * (`tenant-consent-android.md` §3.2: the version column is redundant here, the expander is already
 * scoped to one version). The privacy note renders always, even for an empty list - it states what
 * this table would show if anyone had accepted, not only what it currently shows. */
@Composable
private fun ConsentAcceptancesTable(
    acceptances: List<ConsentAcceptance>,
    onCopied: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.consent_acceptances_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (acceptances.isEmpty()) {
            Text(
                text = stringResource(R.string.consent_acceptances_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row {
                Text(
                    text = stringResource(R.string.consent_acceptances_column_who),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.consent_acceptances_column_accepted_at),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider()
            acceptances.forEach { acceptance -> ConsentAcceptanceRow(acceptance = acceptance, onCopied = onCopied) }
        }
    }
}

/** One acceptance row - a long-press copies the full [ConsentAcceptance.subjectId]
 * (`tenant-consent-android.md` §3.2's own decision: an 8-char short code is enough to correlate two
 * rows on a phone, and long-press-to-copy recovers the full id when genuinely needed - no information
 * destroyed, only deferred behind a gesture). Uses [LocalClipboard]/[ClipEntry], the identical shape
 * [ago.chat.android.channels.ChannelConnectScreen]'s own `VkRevealField` already establishes for a
 * copy action. */
@Composable
private fun ConsentAcceptanceRow(
    acceptance: ConsentAcceptance,
    onCopied: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val who =
        if (acceptance.subjectKind == "Visitor") {
            stringResource(R.string.consent_acceptances_subject_visitor)
        } else {
            acceptance.subjectKind
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ago-consent-subject-id", acceptance.subjectId)))
                            onCopied()
                        }
                    },
                ).padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = who, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = acceptance.subjectId.take(SUBJECT_ID_SHORT_LENGTH),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = consentAcceptedAtText(acceptance.acceptedAt),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** `tenant-consent-android.md` §3.2's own "8-char mono short code". */
private const val SUBJECT_ID_SHORT_LENGTH = 8

private enum class BadgeTone { Info, Danger }

/** The binding-status badge - an `Alert`-shaped tonal surface, danger/info the same two roles
 * [ago.chat.android.automation.CannedResponsesScreen]'s own private `InlineAlert` already uses for
 * danger; info reuses Material 3's `secondaryContainer`, the app's one other tonal-surface pair, since
 * neither [ago.chat.android.ui.theme.AgoStatusColors] nor Material 3 itself has a dedicated "info"
 * role. */
@Composable
private fun ConsentBadge(
    text: String,
    tone: BadgeTone,
) {
    val containerColor =
        when (tone) {
            BadgeTone.Info -> MaterialTheme.colorScheme.secondaryContainer
            BadgeTone.Danger -> MaterialTheme.colorScheme.errorContainer
        }
    val contentColor =
        when (tone) {
            BadgeTone.Info -> MaterialTheme.colorScheme.onSecondaryContainer
            BadgeTone.Danger -> MaterialTheme.colorScheme.onErrorContainer
        }
    Surface(color = containerColor, contentColor = contentColor, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

/** `26-128`: the expandable row's own chevron, restated from
 * [ago.chat.android.shell.SettingsScreen]'s own private `ExpandChevron` rather than shared - neither
 * file imports composables from the other. */
@Composable
private fun ExpandChevron(expanded: Boolean) {
    Icon(
        imageVector = AgoIcons.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .size(20.dp)
                .rotate(if (expanded) 90f else 0f),
    )
}

@Composable
private fun purposeTitleRes(purpose: ConsentPurpose): Int =
    when (purpose) {
        ConsentPurpose.Contact -> R.string.consent_contact_panel_title
        ConsentPurpose.Marketing -> R.string.consent_marketing_panel_title
    }

/** [ConsentVersion.publishedAt] rendered in the operator's own zone (CLAUDE.md rule 11), a date only -
 * the identical device-zone idiom [ago.chat.android.bookings.PendingBookingsScreen]'s own row date
 * already establishes. */
@Composable
private fun consentVersionDate(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofPattern(CONSENT_VERSION_DATE_PATTERN, locale).format(instant.atZone(ZoneId.systemDefault()))
}

/** [ConsentAcceptance.acceptedAt] rendered in the operator's own zone, date and time - the identical
 * pattern [ago.chat.android.thread.contactpanel.sections.NotesSection]'s own `noteTimestamp` already
 * uses. */
@Composable
private fun consentAcceptedAtText(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofPattern(CONSENT_ACCEPTANCE_TIMESTAMP_PATTERN, locale).format(instant.atZone(ZoneId.systemDefault()))
}

private const val CONSENT_VERSION_DATE_PATTERN = "d MMMM yyyy"
private const val CONSENT_ACCEPTANCE_TIMESTAMP_PATTERN = "d MMMM, HH:mm"

/** [consentPublishActionErrorText]'s own four reasons, the identical single-call-site discipline
 * [ago.chat.android.automation.cannedResponsesActionErrorText] already establishes. */
@Composable
internal fun consentPublishActionErrorText(error: ConsentPublishActionError): String =
    when (error) {
        is ConsentPublishActionError.Invalid -> consentPublishValidationProblemText(error.problem)
        ConsentPublishActionError.Conflict -> stringResource(R.string.consent_publish_conflict_message)
        is ConsentPublishActionError.ServerRefusal -> error.detail
        is ConsentPublishActionError.Unavailable -> networkFailureText(error.reason)
    }

@Composable
private fun consentPublishValidationProblemText(problem: ConsentPublishValidationProblem): String =
    when (problem) {
        ConsentPublishValidationProblem.TitleRequired -> stringResource(R.string.consent_publish_validation_title_required)
        ConsentPublishValidationProblem.TitleTooLong ->
            stringResource(R.string.consent_publish_validation_title_too_long, ConsentDocumentBounds.MAX_TITLE_LENGTH)

        ConsentPublishValidationProblem.BodyRequired -> stringResource(R.string.consent_publish_validation_body_required)
        ConsentPublishValidationProblem.BodyTooLong ->
            stringResource(R.string.consent_publish_validation_body_too_long, ConsentDocumentBounds.MAX_BODY_LENGTH)
    }
