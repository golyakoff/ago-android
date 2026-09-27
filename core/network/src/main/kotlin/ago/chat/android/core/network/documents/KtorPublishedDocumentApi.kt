package ago.chat.android.core.network.documents

import ago.chat.android.core.domain.documents.PublishedDocument
import ago.chat.android.core.domain.documents.PublishedDocumentApi
import ago.chat.android.core.domain.documents.PublishedDocumentResult
import ago.chat.android.core.domain.net.NetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * `26-228` (`docs/design/tenant-consent-android.md` §2.3): the adapter behind [PublishedDocumentApi] —
 * the same "the whole status-code-to-meaning mapping lives here, and only here" shape
 * [ago.chat.android.core.network.consent.KtorSiteConsentDocumentsApi] already establishes.
 *
 * **No [ago.chat.android.core.domain.identity.ActiveSiteSelection], deliberately** — unlike every other
 * adapter in `:core:network` that reads a `{siteId}`-scoped route, `/api/v1/documents/{documentKey}` is
 * `AllowAnonymous` on the server (`DocumentEndpoints`'s own remark: "somebody who has not yet accepted
 * anything has no account to read it from"), and carries no site at all — the `documentKey` alone
 * addresses the row. The bearer token this app's client attaches by default
 * (`installAgoRestDefaults`) simply rides along unused; the server never asks for it on this route.
 *
 * **A parse failure on an otherwise-2xx body is [NetworkFailure.Unexpected], never an empty read** — the
 * identical `KtorConversationTagsApi`/`shapeGuard.ts` lesson every adapter in this app already follows.
 */
public class KtorPublishedDocumentApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : PublishedDocumentApi {
    /**
     * `version == null` → `GET /api/v1/documents/{documentKey}` (current); otherwise
     * `GET /api/v1/documents/{documentKey}/versions/{version}` (a specific, immutable one).
     */
    override suspend fun fetchDocument(
        documentKey: String,
        version: String?,
    ): PublishedDocumentResult {
        val url =
            if (version != null) {
                "$apiBaseUrl/api/v1/documents/$documentKey/versions/$version"
            } else {
                "$apiBaseUrl/api/v1/documents/$documentKey"
            }

        val response =
            try {
                client.get(url)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return PublishedDocumentResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status == HttpStatusCode.NotFound) {
            return PublishedDocumentResult.NotFound
        }

        if (!response.status.isSuccess()) {
            // 429 (the per-IP rate limit) lands here too - a plain retry-able failure, the server's own
            // `Retry-After` is not honoured with a timer on this read (`tenant-consent-android.md` §1.4's
            // own "surface the server said busy" posture, the same the channel logo-upload limit gets).
            return PublishedDocumentResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            PublishedDocumentResult.Loaded(response.body<DocumentBodyWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            PublishedDocumentResult.Failed(NetworkFailure.from(failure))
        }
    }
}

/** `DocumentEndpoints.DocumentVersionResponse`, field for field - `sequence` is read from the wire but
 * never carried onto [PublishedDocument]: the reader draws version + date, never a sequence number,
 * the identical "never parse back what a screen has no use for" discipline every wire DTO in this app
 * already follows. */
@Serializable
private data class DocumentBodyWireDto(
    val documentKey: String,
    val version: String,
    val sequence: Int,
    val title: String,
    val body: String,
    val publishedAt: String,
)

private fun DocumentBodyWireDto.toDomain() =
    PublishedDocument(
        documentKey = documentKey,
        version = version,
        title = title,
        body = body,
        publishedAt = OffsetDateTime.parse(publishedAt).toInstant(),
    )
