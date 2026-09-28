package ago.chat.android.core.domain.storage

import ago.chat.android.core.domain.net.NetworkFailure
import java.time.Instant

/**
 * `26-250` (`ago-console`'s own `StoragePage` + `siteAttachmentStorageApi.ts`, ported): the port behind
 * Администрирование → «Хранилище» — the four reads the console draws in one `Promise.all`
 * (`GET /api/v1/sites/{siteId}/attachments…` for the list, `/storage-summary`, `/egress`,
 * `/largest-conversations`) and the one destructive write (`POST …/attachments/bulk-delete`).
 * `site:configure` on every verb *inside the handler* (`SiteAttachmentStorageEndpoints`, `ago-chat`),
 * the same permission the row itself is gated on — the identical "no rail-vs-server gap" shape
 * [ago.chat.android.core.domain.consent.SiteConsentDocumentsApi] already establishes for its own
 * site-scoped settings surface.
 *
 * Declared here, implemented in `:core:network` (`KtorSiteAttachmentStorageApi`) — the dependency rule
 * is what puts it here rather than beside the Ktor client: a view model holding an `HttpClient` directly
 * could not be tested without one, and every HTTP-shaped decision (which status means what, which base
 * URL, which `{siteId}`, which query params) belongs on the far side of this interface, in the adapter.
 */
public interface SiteAttachmentStorageApi {
    /**
     * `GET /api/v1/sites/{siteId}/attachments/storage-summary`. The quota bar's own numbers — this
     * item's own "the thing the author asked for first" (`StoragePage`'s own doc comment).
     */
    public suspend fun fetchStorageSummary(): StorageSummaryResult

    /**
     * `GET /api/v1/sites/{siteId}/attachments/egress`. This month's download count and bytes-out —
     * a small, independent read the console draws under the quota bar.
     */
    public suspend fun fetchEgress(): AttachmentEgressResult

    /**
     * `GET /api/v1/sites/{siteId}/attachments/largest-conversations`. A raw JSON array, newest-heaviest
     * first — the console's own "where is it all" summary panel.
     */
    public suspend fun fetchLargestConversations(): LargestConversationsResult

    /**
     * `GET /api/v1/sites/{siteId}/attachments?sort=&filter=&cursorValue=&cursorAttachmentId=`. One page
     * of the attachments table; a `null` [cursor] reads the first page, a non-`null` one continues from
     * where [AttachmentListPage.nextCursor] left off — the identical keyset shape the console's own
     * `fetchSiteAttachments` threads.
     */
    public suspend fun fetchAttachments(
        sort: AttachmentListSort,
        filter: AttachmentListFilter,
        cursor: AttachmentListCursor? = null,
    ): AttachmentListResult

    /**
     * `POST /api/v1/sites/{siteId}/attachments/bulk-delete`, body `{attachmentIds}`. **Destructive and
     * irreversible** — the bytes leave object storage for good, so the caller (`:app`) fires this only
     * behind an explicit confirmation. Returns the server's own tally ([BulkDeleteOutcome]) so the
     * screen can report exactly what went ("удалено N, освобождено X") rather than assume its whole
     * selection landed.
     */
    public suspend fun bulkDelete(attachmentIds: List<String>): BulkDeleteResult
}

/** `AttachmentListSort`'s wire spellings, verbatim — [slug] is exactly what the `sort` query param
 * carries, never derived or guessed at a call site. */
public enum class AttachmentListSort(
    public val slug: String,
) {
    SizeDesc("sizeDesc"),
    TypeAsc("typeAsc"),
    AgeAsc("ageAsc"),
    ConversationAsc("conversationAsc"),
    SenderAsc("senderAsc"),
}

/** `AttachmentListFilter`'s wire spellings, verbatim — the `filter` query param's own values. */
public enum class AttachmentListFilter(
    public val slug: String,
) {
    None("none"),
    NeverDownloaded("neverDownloaded"),
    Duplicates("duplicates"),
}

/** One attachment row's metadata. **No original file name** — `Attachment` (`ago-chat`) never captured
 * one (`StoragePage`'s own doc comment), so [contentType] stands in for it, exactly as the console's own
 * table does. [senderKind]/[senderId] are nullable: a system-authored attachment has no sender. */
public data class AttachmentListItem(
    val id: String,
    val conversationId: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Instant,
    val downloadCount: Int,
    val lastDownloadedAt: Instant?,
    val senderKind: String?,
    val senderId: String?,
    val isDuplicate: Boolean,
)

/** The keyset cursor for the next page — both halves are opaque to the app, echoed straight back to the
 * server as `cursorValue`/`cursorAttachmentId`. */
public data class AttachmentListCursor(
    val value: String,
    val attachmentId: String,
)

/** One page of the attachments table. [nextCursor] is `null` on the last page. */
public data class AttachmentListPage(
    val items: List<AttachmentListItem>,
    val nextCursor: AttachmentListCursor?,
)

/** One conversation's own attachment weight — the "largest conversations" summary row. */
public data class LargestConversation(
    val conversationId: String,
    val totalBytes: Long,
    val attachmentCount: Int,
)

/** The quota read — bytes held against the site's own allowance. */
public data class StorageSummary(
    val usedBytes: Long,
    val totalBytes: Long,
)

/** This month's download figures — [periodMonth] is the server's own `YYYY-MM` label, shown verbatim. */
public data class AttachmentEgress(
    val periodMonth: String,
    val downloadCount: Int,
    val bytesOut: Long,
)

/** The server's own bulk-delete tally. [deletedCount]/[freedBytes] are what the screen reports; the two
 * "was already gone" fields are carried so a future caller can distinguish a partial delete from a full
 * one without a second round trip, even though this screen only surfaces the first two. */
public data class BulkDeleteOutcome(
    val deletedCount: Int,
    val freedBytes: Long,
    val notFoundIds: List<String>,
    val alreadyGoneCount: Int,
)

/** What reading the quota summary came back with — the identical two-arm shape
 * [ago.chat.android.core.domain.consent.SiteConsentDocumentsResult] already establishes. */
public sealed interface StorageSummaryResult {
    public data class Loaded(
        val summary: StorageSummary,
    ) : StorageSummaryResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : StorageSummaryResult
}

/** What reading this month's egress came back with. */
public sealed interface AttachmentEgressResult {
    public data class Loaded(
        val egress: AttachmentEgress,
    ) : AttachmentEgressResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : AttachmentEgressResult
}

/** What reading the largest-conversations summary came back with. A loaded-but-empty list is a real,
 * expected state (no attachments yet), never a failure. */
public sealed interface LargestConversationsResult {
    public data class Loaded(
        val conversations: List<LargestConversation>,
    ) : LargestConversationsResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : LargestConversationsResult
}

/** What reading one page of the attachments table came back with. A loaded-but-empty page is a real,
 * expected state (nothing matches the filter), never a failure. */
public sealed interface AttachmentListResult {
    public data class Loaded(
        val page: AttachmentListPage,
    ) : AttachmentListResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : AttachmentListResult
}

/** What the destructive bulk-delete came back with — two arms, the write either landed (carrying the
 * server's own tally) or it did not. There is no partial-failure arm: a `2xx` whose body says "3 of 5
 * were already gone" is still a [Deleted], because the request itself succeeded — the tally is data, not
 * an error. */
public sealed interface BulkDeleteResult {
    public data class Deleted(
        val outcome: BulkDeleteOutcome,
    ) : BulkDeleteResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : BulkDeleteResult
}
