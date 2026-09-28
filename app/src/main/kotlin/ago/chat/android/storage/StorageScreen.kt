package ago.chat.android.storage

import ago.chat.android.R
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.storage.AttachmentEgress
import ago.chat.android.core.domain.storage.AttachmentListFilter
import ago.chat.android.core.domain.storage.AttachmentListItem
import ago.chat.android.core.domain.storage.AttachmentListSort
import ago.chat.android.core.domain.storage.BulkDeleteOutcome
import ago.chat.android.core.domain.storage.LargestConversation
import ago.chat.android.core.domain.storage.StorageSummary
import ago.chat.android.ui.components.SectionLabel
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

/**
 * `26-250` (`ago-console`'s own `StoragePage`, ported): Ещё → Администрирование → «Хранилище» — the quota
 * bar, this month's egress, the heaviest conversations, and the sortable/filterable attachments table with
 * bulk-select-and-delete. **The delete is destructive and irreversible**, so it fires only through an
 * explicit [AlertDialog] that names how many attachments and how many bytes are about to leave object
 * storage for good. Obtains its own [StorageViewModel] via [hiltViewModel], the identical wiring
 * [ago.chat.android.products.ProductsRoute] establishes for its own Ещё drill-in.
 *
 * `MoreScreen` composes this row only for an operator holding `site:configure` (hide-not-disable), the same
 * permission `ago-console`'s own `StoragePage` gates `/account/storage` on — so the gate lives one level up
 * in the row list, not as an in-screen check, exactly as every other real Ещё row gates itself.
 *
 * **No original file-name column.** `Attachment` (`ago-chat`) never captured one, so the content type
 * stands in for it, exactly as the console's own table does (`StoragePage`'s own doc comment).
 */
@Composable
internal fun StorageRoute(
    onBack: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StorageScreen(
        state = state,
        onRetry = {
            val loaded = state as? StorageUiState.Loaded
            viewModel.refresh(
                sort = loaded?.sort ?: AttachmentListSort.SizeDesc,
                filter = loaded?.filter ?: AttachmentListFilter.None,
            )
        },
        onSortChange = viewModel::changeSort,
        onFilterChange = viewModel::changeFilter,
        onToggleSelected = viewModel::toggleSelected,
        onRequestDelete = viewModel::requestDelete,
        onCancelDelete = viewModel::cancelDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onLoadMore = viewModel::loadMore,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageScreen(
    state: StorageUiState,
    onRetry: () -> Unit,
    onSortChange: (AttachmentListSort) -> Unit,
    onFilterChange: (AttachmentListFilter) -> Unit,
    onToggleSelected: (String) -> Unit,
    onRequestDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onLoadMore: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.storage_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        TextButton(onClick = onRetry) {
                            Text(text = stringResource(R.string.storage_refresh_action))
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    StorageUiState.Loading -> LoadingBody()
                    is StorageUiState.Failed -> StorageFailedBody(reason = state.reason, onRetry = onRetry)
                    is StorageUiState.Loaded ->
                        StorageLoadedBody(
                            state = state,
                            onSortChange = onSortChange,
                            onFilterChange = onFilterChange,
                            onToggleSelected = onToggleSelected,
                            onRequestDelete = onRequestDelete,
                            onLoadMore = onLoadMore,
                        )
                }
            }

            if (state is StorageUiState.Loaded && state.confirmingDelete) {
                DeleteConfirmDialog(
                    count = state.selected.size,
                    bytes = state.selectedBytes,
                    deleting = state.deleting,
                    onConfirm = onConfirmDelete,
                    onDismiss = onCancelDelete,
                )
            }
        }
    }
}

/** The whole-screen read failure — the identical "message, retry" shape
 * [ago.chat.android.products.ProductsScreen]'s own failed body establishes. */
@Composable
private fun StorageFailedBody(
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
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun StorageLoadedBody(
    state: StorageUiState.Loaded,
    onSortChange: (AttachmentListSort) -> Unit,
    onFilterChange: (AttachmentListFilter) -> Unit,
    onToggleSelected: (String) -> Unit,
    onRequestDelete: () -> Unit,
    onLoadMore: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "quota") {
            QuotaPanel(summary = state.summary, egress = state.egress)
            HorizontalDivider()
        }

        if (state.largest.isNotEmpty()) {
            item(key = "largest-header") { SectionLabel(stringResource(R.string.storage_largest_title)) }
            items(state.largest, key = { "largest-${it.conversationId}" }) { conversation ->
                LargestConversationRow(conversation)
            }
            item(key = "largest-divider") { HorizontalDivider() }
        }

        state.lastDelete?.let { outcome ->
            item(key = "delete-result") { DeleteResultBanner(outcome) }
        }

        state.actionError?.let { reason ->
            item(key = "action-error") {
                Text(
                    text = networkFailureText(reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        item(key = "controls") {
            SectionLabel(stringResource(R.string.storage_table_title))
            StorageControls(
                sort = state.sort,
                filter = state.filter,
                selectedCount = state.selected.size,
                enabled = !state.listBusy && !state.deleting,
                onSortChange = onSortChange,
                onFilterChange = onFilterChange,
                onRequestDelete = onRequestDelete,
            )
        }

        if (state.items.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.storage_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            items(state.items, key = { it.id }) { item ->
                AttachmentRow(
                    item = item,
                    selected = item.id in state.selected,
                    onToggle = { onToggleSelected(item.id) },
                )
                HorizontalDivider()
            }
            if (state.nextCursor != null) {
                item(key = "load-more") {
                    TextButton(
                        onClick = onLoadMore,
                        enabled = !state.listBusy && !state.deleting,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text =
                                if (state.listBusy) {
                                    stringResource(R.string.storage_loading)
                                } else {
                                    stringResource(R.string.storage_load_more)
                                },
                        )
                    }
                }
            }
        }
    }
}

/** The quota bar — used-of-total, a progress bar, and this month's egress line under it. This item's own
 * "the thing the author asked for first" (`StoragePage`'s own doc comment). */
@Composable
private fun QuotaPanel(
    summary: StorageSummary,
    egress: AttachmentEgress?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text =
                stringResource(
                    R.string.storage_quota_used,
                    formatByteSize(summary.usedBytes),
                    formatByteSize(summary.totalBytes),
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
        val fraction =
            if (summary.totalBytes <= 0L) {
                0f
            } else {
                (summary.usedBytes.toFloat() / summary.totalBytes.toFloat()).coerceIn(0f, 1f)
            }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        egress?.let {
            Text(
                text =
                    stringResource(
                        R.string.storage_egress_this_month,
                        it.periodMonth,
                        it.downloadCount,
                        formatByteSize(it.bytesOut),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun LargestConversationRow(conversation: LargestConversation) {
    Text(
        text =
            stringResource(
                R.string.storage_largest_row,
                conversation.conversationId.take(CONVERSATION_ID_PREFIX_LENGTH),
                formatByteSize(conversation.totalBytes),
                conversation.attachmentCount,
            ),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun DeleteResultBanner(outcome: BulkDeleteOutcome) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text =
                stringResource(
                    R.string.storage_delete_result,
                    outcome.deletedCount,
                    formatByteSize(outcome.freedBytes),
                ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** The sort/filter dropdowns and the danger delete button — the console's own two `Select`s and its danger
 * `Button` with the live selection count. */
@Composable
private fun StorageControls(
    sort: AttachmentListSort,
    filter: AttachmentListFilter,
    selectedCount: Int,
    enabled: Boolean,
    onSortChange: (AttachmentListSort) -> Unit,
    onFilterChange: (AttachmentListFilter) -> Unit,
    onRequestDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownSelector(
                label = stringResource(sort.labelRes()),
                enabled = enabled,
                options = AttachmentListSort.entries,
                optionLabel = { stringResource(it.labelRes()) },
                onSelect = onSortChange,
                modifier = Modifier.weight(1f),
            )
            DropdownSelector(
                label = stringResource(filter.labelRes()),
                enabled = enabled,
                options = AttachmentListFilter.entries,
                optionLabel = { stringResource(it.labelRes()) },
                onSelect = onFilterChange,
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = onRequestDelete,
            enabled = enabled && selectedCount > 0,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(
                text =
                    if (selectedCount > 0) {
                        stringResource(R.string.storage_delete_selected_count, selectedCount)
                    } else {
                        stringResource(R.string.storage_delete_selected)
                    },
            )
        }
    }
}

/** A minimal single-choice dropdown — an [OutlinedButton] showing the current label that opens a
 * [DropdownMenu] of the options. Generic over the enum it drives, so sort and filter share it. */
@Composable
private fun <T> DropdownSelector(
    label: String,
    enabled: Boolean,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(text = label, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun AttachmentRow(
    item: AttachmentListItem,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = item.contentType,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                )
                Text(text = formatByteSize(item.sizeBytes), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text =
                    stringResource(
                        R.string.storage_row_meta,
                        item.conversationId.take(CONVERSATION_ID_PREFIX_LENGTH),
                        senderLabel(item.senderKind),
                        formatDate(item.createdAt),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text =
                        if (item.downloadCount == 0) {
                            stringResource(R.string.storage_never_downloaded)
                        } else {
                            stringResource(R.string.storage_downloads_count, item.downloadCount)
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.isDuplicate) {
                    Text(
                        text = stringResource(R.string.storage_duplicate_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** The destructive-confirm dialog — names the count and the bytes about to be **permanently** deleted
 * before the operator can fire it, the identical [AlertDialog] shape
 * [ago.chat.android.automation.TagsScreen]'s own delete confirm establishes, hardened here with a
 * plural-correct message because a bulk delete's count is never fixed at one. */
@Composable
private fun DeleteConfirmDialog(
    count: Int,
    bytes: Long,
    deleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(text = stringResource(R.string.storage_confirm_delete_title)) },
        text = {
            Text(text = stringResource(R.string.storage_confirm_delete_message, count, formatByteSize(bytes)))
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deleting) {
                Text(
                    text =
                        if (deleting) {
                            stringResource(R.string.storage_loading)
                        } else {
                            stringResource(R.string.storage_confirm_delete_action)
                        },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

/** The friendly sender label — never the raw `senderKind` wire string; an unknown or absent kind reads as
 * «Система/—» via the unknown branch. */
@Composable
private fun senderLabel(kind: String?): String =
    when (kind) {
        "Visitor" -> stringResource(R.string.storage_sender_visitor)
        "Operator" -> stringResource(R.string.storage_sender_operator)
        "System" -> stringResource(R.string.storage_sender_system)
        "AutoGreeting" -> stringResource(R.string.storage_sender_auto_greeting)
        else -> stringResource(R.string.storage_sender_unknown)
    }

private fun AttachmentListSort.labelRes(): Int =
    when (this) {
        AttachmentListSort.SizeDesc -> R.string.storage_sort_size_desc
        AttachmentListSort.TypeAsc -> R.string.storage_sort_type_asc
        AttachmentListSort.AgeAsc -> R.string.storage_sort_age_asc
        AttachmentListSort.ConversationAsc -> R.string.storage_sort_conversation_asc
        AttachmentListSort.SenderAsc -> R.string.storage_sort_sender_asc
    }

private fun AttachmentListFilter.labelRes(): Int =
    when (this) {
        AttachmentListFilter.None -> R.string.storage_filter_none
        AttachmentListFilter.NeverDownloaded -> R.string.storage_filter_never_downloaded
        AttachmentListFilter.Duplicates -> R.string.storage_filter_duplicates
    }

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.getDefault())

private fun formatDate(instant: Instant): String = dateFormatter.format(instant.atZone(ZoneId.systemDefault()))

/** `ago-console`'s own `formatByteSize`, ported key for key: binary units, the byte case shown whole and
 * every larger unit truncated (never rounded) to one decimal, so a bar never claims more freed than it
 * actually did. Negative is a garbled-response guard, not a meaningful state. */
internal fun formatByteSize(bytes: Long): String {
    val safe = if (bytes < 0L) 0L else bytes
    if (safe < BYTES_PER_UNIT) {
        return "$safe B"
    }
    var value = safe.toDouble()
    var unit = 0
    while (value >= BYTES_PER_UNIT && unit < BYTE_UNITS.lastIndex) {
        value /= BYTES_PER_UNIT
        unit += 1
    }
    val truncated = floor(value * 10.0) / 10.0
    return String.format(Locale.US, "%.1f %s", truncated, BYTE_UNITS[unit])
}

private const val BYTES_PER_UNIT = 1024.0
private const val CONVERSATION_ID_PREFIX_LENGTH = 8
private val BYTE_UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
