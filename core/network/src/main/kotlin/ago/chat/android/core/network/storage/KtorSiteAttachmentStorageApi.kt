package ago.chat.android.core.network.storage

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.storage.AttachmentEgress
import ago.chat.android.core.domain.storage.AttachmentEgressResult
import ago.chat.android.core.domain.storage.AttachmentListCursor
import ago.chat.android.core.domain.storage.AttachmentListFilter
import ago.chat.android.core.domain.storage.AttachmentListItem
import ago.chat.android.core.domain.storage.AttachmentListPage
import ago.chat.android.core.domain.storage.AttachmentListResult
import ago.chat.android.core.domain.storage.AttachmentListSort
import ago.chat.android.core.domain.storage.BulkDeleteOutcome
import ago.chat.android.core.domain.storage.BulkDeleteResult
import ago.chat.android.core.domain.storage.LargestConversation
import ago.chat.android.core.domain.storage.LargestConversationsResult
import ago.chat.android.core.domain.storage.SiteAttachmentStorageApi
import ago.chat.android.core.domain.storage.StorageSummary
import ago.chat.android.core.domain.storage.StorageSummaryResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

/**
 * `26-250` (`ago-console`'s own `siteAttachmentStorageApi.ts`, ported): the adapter behind
 * [SiteAttachmentStorageApi] — the same "the whole status-code-to-meaning mapping lives here, and only
 * here" shape [ago.chat.android.core.network.consent.KtorSiteConsentDocumentsApi] already establishes.
 *
 * [ActiveSiteSelection.currentSiteId] is read directly here, for every method — the identical reason the
 * consent adapter's own doc comment gives: `{siteId}` is in the URL itself, not only in the
 * `X-Ago-Active-Site` header every request already gets from `installAgoRestDefaults`. The bearer token
 * is attached by that same client plugin, never threaded through any method here.
 *
 * **A parse failure on an otherwise-2xx body is [NetworkFailure.Unexpected], never a fabricated read** —
 * the identical `shapeGuard.ts` discipline every adapter in this app follows. Timestamps are parsed with
 * [OffsetDateTime.parse] inside the surrounding body-to-domain `try`/`catch`, so a malformed one becomes
 * [NetworkFailure.Unexpected] rather than a fabricated `null`.
 */
public class KtorSiteAttachmentStorageApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : SiteAttachmentStorageApi {
    /** `GET /api/v1/sites/{siteId}/attachments/storage-summary`. */
    override suspend fun fetchStorageSummary(): StorageSummaryResult {
        val siteId = activeSite.currentSiteId() ?: return StorageSummaryResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get("${attachmentsUrl(siteId)}/storage-summary")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return StorageSummaryResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return StorageSummaryResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            StorageSummaryResult.Loaded(response.body<StorageSummaryWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            StorageSummaryResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `GET /api/v1/sites/{siteId}/attachments/egress`. */
    override suspend fun fetchEgress(): AttachmentEgressResult {
        val siteId = activeSite.currentSiteId() ?: return AttachmentEgressResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get("${attachmentsUrl(siteId)}/egress")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return AttachmentEgressResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return AttachmentEgressResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            AttachmentEgressResult.Loaded(response.body<EgressWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            AttachmentEgressResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `GET /api/v1/sites/{siteId}/attachments/largest-conversations` — a raw JSON array, no wrapper. */
    override suspend fun fetchLargestConversations(): LargestConversationsResult {
        val siteId = activeSite.currentSiteId() ?: return LargestConversationsResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get("${attachmentsUrl(siteId)}/largest-conversations")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return LargestConversationsResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return LargestConversationsResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            LargestConversationsResult.Loaded(response.body<List<LargestConversationWireDto>>().map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            LargestConversationsResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `GET /api/v1/sites/{siteId}/attachments?sort=&filter=&cursorValue=&cursorAttachmentId=`. The two
     * cursor params are set only when [cursor] is non-`null`, exactly as the console's own
     * `URLSearchParams` build does. */
    override suspend fun fetchAttachments(
        sort: AttachmentListSort,
        filter: AttachmentListFilter,
        cursor: AttachmentListCursor?,
    ): AttachmentListResult {
        val siteId = activeSite.currentSiteId() ?: return AttachmentListResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get(attachmentsUrl(siteId)) {
                    parameter("sort", sort.slug)
                    parameter("filter", filter.slug)
                    if (cursor != null) {
                        parameter("cursorValue", cursor.value)
                        parameter("cursorAttachmentId", cursor.attachmentId)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return AttachmentListResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return AttachmentListResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            AttachmentListResult.Loaded(response.body<AttachmentPageWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            AttachmentListResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `POST /api/v1/sites/{siteId}/attachments/bulk-delete`, body `{attachmentIds}`. Any non-2xx is a
     * failure carrying the server's own status — there is no per-id refusal to render, unlike the
     * consent publish's `409`; the whole request either lands or it does not. */
    override suspend fun bulkDelete(attachmentIds: List<String>): BulkDeleteResult {
        val siteId = activeSite.currentSiteId() ?: return BulkDeleteResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post("${attachmentsUrl(siteId)}/bulk-delete") {
                    contentType(ContentType.Application.Json)
                    setBody(BulkDeleteRequestWireDto(attachmentIds = attachmentIds))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return BulkDeleteResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return BulkDeleteResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            BulkDeleteResult.Deleted(response.body<BulkDeleteResponseWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            BulkDeleteResult.Failed(NetworkFailure.from(failure))
        }
    }

    private fun attachmentsUrl(siteId: String): String = "$apiBaseUrl/api/v1/sites/$siteId/attachments"
}

/** `SiteAttachmentStorageEndpoints.AttachmentStorageSummaryResponse`, field for field. */
@Serializable
private data class StorageSummaryWireDto(
    val usedBytes: Long,
    val totalBytes: Long,
)

/** `SiteAttachmentStorageEndpoints.AttachmentEgressResponse`. */
@Serializable
private data class EgressWireDto(
    val periodMonth: String,
    val downloadCount: Int,
    val bytesOut: Long,
)

/** `SiteAttachmentStorageEndpoints.LargestConversationResponse`. */
@Serializable
private data class LargestConversationWireDto(
    val conversationId: String,
    val totalBytes: Long,
    val attachmentCount: Int,
)

/** `SiteAttachmentStorageEndpoints.AttachmentListItemResponse`. `lastDownloadedAt`/`senderKind`/
 * `senderId` are nullable on the wire and default to `null` so a body that omits them still parses. */
@Serializable
private data class AttachmentListItemWireDto(
    val id: String,
    val conversationId: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: String,
    val downloadCount: Int,
    val lastDownloadedAt: String? = null,
    val senderKind: String? = null,
    val senderId: String? = null,
    val isDuplicate: Boolean = false,
)

/** `SiteAttachmentStorageEndpoints.AttachmentListCursorResponse`. */
@Serializable
private data class AttachmentListCursorWireDto(
    val value: String,
    val attachmentId: String,
)

/** `SiteAttachmentStorageEndpoints.AttachmentListPageResponse`. [nextCursor] is `null` on the last page. */
@Serializable
private data class AttachmentPageWireDto(
    val items: List<AttachmentListItemWireDto> = emptyList(),
    val nextCursor: AttachmentListCursorWireDto? = null,
)

/** The `POST` request body — no `siteId`, the route itself already names it. */
@Serializable
private data class BulkDeleteRequestWireDto(
    val attachmentIds: List<String>,
)

/** `SiteAttachmentStorageEndpoints.BulkDeleteAttachmentsResponse`. */
@Serializable
private data class BulkDeleteResponseWireDto(
    val deletedCount: Int,
    val freedBytes: Long,
    val notFoundIds: List<String> = emptyList(),
    val alreadyGoneCount: Int = 0,
)

private fun String.toInstant(): Instant = OffsetDateTime.parse(this).toInstant()

private fun StorageSummaryWireDto.toDomain() = StorageSummary(usedBytes = usedBytes, totalBytes = totalBytes)

private fun EgressWireDto.toDomain() = AttachmentEgress(periodMonth = periodMonth, downloadCount = downloadCount, bytesOut = bytesOut)

private fun LargestConversationWireDto.toDomain() =
    LargestConversation(conversationId = conversationId, totalBytes = totalBytes, attachmentCount = attachmentCount)

private fun AttachmentListItemWireDto.toDomain() =
    AttachmentListItem(
        id = id,
        conversationId = conversationId,
        contentType = contentType,
        sizeBytes = sizeBytes,
        createdAt = createdAt.toInstant(),
        downloadCount = downloadCount,
        lastDownloadedAt = lastDownloadedAt?.toInstant(),
        senderKind = senderKind,
        senderId = senderId,
        isDuplicate = isDuplicate,
    )

private fun AttachmentListCursorWireDto.toDomain() = AttachmentListCursor(value = value, attachmentId = attachmentId)

private fun AttachmentPageWireDto.toDomain() = AttachmentListPage(items = items.map { it.toDomain() }, nextCursor = nextCursor?.toDomain())

private fun BulkDeleteResponseWireDto.toDomain() =
    BulkDeleteOutcome(
        deletedCount = deletedCount,
        freedBytes = freedBytes,
        notFoundIds = notFoundIds,
        alreadyGoneCount = alreadyGoneCount,
    )
