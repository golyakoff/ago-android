package ago.chat.android.core.network.storage

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.storage.AttachmentEgress
import ago.chat.android.core.domain.storage.AttachmentEgressResult
import ago.chat.android.core.domain.storage.AttachmentListCursor
import ago.chat.android.core.domain.storage.AttachmentListFilter
import ago.chat.android.core.domain.storage.AttachmentListResult
import ago.chat.android.core.domain.storage.AttachmentListSort
import ago.chat.android.core.domain.storage.BulkDeleteOutcome
import ago.chat.android.core.domain.storage.BulkDeleteResult
import ago.chat.android.core.domain.storage.LargestConversation
import ago.chat.android.core.domain.storage.LargestConversationsResult
import ago.chat.android.core.domain.storage.StorageSummary
import ago.chat.android.core.domain.storage.StorageSummaryResult
import ago.chat.android.core.network.InMemoryActiveSite
import ago.chat.android.core.network.MutableAccessTokenProvider
import ago.chat.android.core.network.installAgoRestDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * `26-250` (`ago-console`'s own `siteAttachmentStorageApi.ts`, ported): the four reads and the destructive
 * bulk-delete, driven through the real client configuration and a `MockEngine` — the identical shape
 * `KtorSiteConsentDocumentsApiTest` already establishes.
 */
class KtorSiteAttachmentStorageApiTest {
    private val baseUrl = "https://chat-api.example.invalid"
    private val siteId = "site-123"

    // ------------------------------------------------- GET /attachments/storage-summary

    @Test
    fun `the quota summary is read with the current site id in the path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond("""{"usedBytes":2048,"totalBytes":10240}""", HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.fetchStorageSummary()

            assertEquals(StorageSummaryResult.Loaded(StorageSummary(usedBytes = 2048, totalBytes = 10240)), result)
            assertEquals("$baseUrl/api/v1/sites/$siteId/attachments/storage-summary", requestedUrl)
        }

    @Test
    fun `no active site on the summary read is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(StorageSummaryResult.Failed(NetworkFailure.Unexpected), api.fetchStorageSummary())
            assertEquals("no active site must never reach the network", 0, calls)
        }

    @Test
    fun `a 5xx on the summary read is a server error`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.ServiceUnavailable) }

            assertEquals(StorageSummaryResult.Failed(NetworkFailure.ServerError(503)), api.fetchStorageSummary())
        }

    @Test
    fun `a 200 summary that dropped the shape is Failed, never a fabricated read`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"somethingElse":true}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(StorageSummaryResult.Failed(NetworkFailure.Unexpected), api.fetchStorageSummary())
        }

    // ------------------------------------------------- GET /attachments/egress

    @Test
    fun `egress is read from the egress path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond("""{"periodMonth":"2026-09","downloadCount":12,"bytesOut":4096}""", HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.fetchEgress()

            assertEquals(
                AttachmentEgressResult.Loaded(AttachmentEgress(periodMonth = "2026-09", downloadCount = 12, bytesOut = 4096)),
                result,
            )
            assertEquals("$baseUrl/api/v1/sites/$siteId/attachments/egress", requestedUrl)
        }

    // ------------------------------------------------- GET /attachments/largest-conversations

    @Test
    fun `largest conversations is a raw array read from its own path`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(
                        """[{"conversationId":"c1","totalBytes":900,"attachmentCount":3},
                            {"conversationId":"c2","totalBytes":100,"attachmentCount":1}]""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.fetchLargestConversations()

            assertEquals("$baseUrl/api/v1/sites/$siteId/attachments/largest-conversations", requestedUrl)
            assertEquals(
                LargestConversationsResult.Loaded(
                    listOf(
                        LargestConversation("c1", 900, 3),
                        LargestConversation("c2", 100, 1),
                    ),
                ),
                result,
            )
        }

    @Test
    fun `an empty largest-conversations array is a loaded result, not a failure`() =
        runTest {
            val api = apiFor(siteId) { respond("[]", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(LargestConversationsResult.Loaded(emptyList()), api.fetchLargestConversations())
        }

    // ------------------------------------------------- GET /attachments (list)

    @Test
    fun `the list is read with sort and filter in the query, no cursor params on the first page`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond(attachmentPageJson(), HttpStatusCode.OK, jsonHeaders())
                }

            val result = api.fetchAttachments(AttachmentListSort.SizeDesc, AttachmentListFilter.NeverDownloaded, cursor = null)

            val loaded = result as AttachmentListResult.Loaded
            assertEquals(1, loaded.page.items.size)
            assertEquals(
                "att-1",
                loaded.page.items
                    .single()
                    .id,
            )
            assertEquals(
                "image/png",
                loaded.page.items
                    .single()
                    .contentType,
            )
            assertEquals(
                1500L,
                loaded.page.items
                    .single()
                    .sizeBytes,
            )
            assertEquals(
                Instant.parse("2026-09-01T08:00:00Z"),
                loaded.page.items
                    .single()
                    .createdAt,
            )
            assertEquals(AttachmentListCursor(value = "1500", attachmentId = "att-1"), loaded.page.nextCursor)
            val url = requireNotNull(requestedUrl)
            assertTrue(url.contains("sort=sizeDesc"))
            assertTrue(url.contains("filter=neverDownloaded"))
            assertFalse(url.contains("cursorValue"))
        }

    @Test
    fun `a cursor is threaded into the query as cursorValue and cursorAttachmentId`() =
        runTest {
            var requestedUrl: String? = null
            val api =
                apiFor(siteId) { request ->
                    requestedUrl = request.url.toString()
                    respond("""{"items":[],"nextCursor":null}""", HttpStatusCode.OK, jsonHeaders())
                }

            api.fetchAttachments(
                AttachmentListSort.TypeAsc,
                AttachmentListFilter.None,
                cursor = AttachmentListCursor(value = "900", attachmentId = "att-9"),
            )

            val url = requireNotNull(requestedUrl)
            assertTrue(url.contains("cursorValue=900"))
            assertTrue(url.contains("cursorAttachmentId=att-9"))
        }

    @Test
    fun `an empty list page with a null cursor is a loaded result, not a failure`() =
        runTest {
            val api = apiFor(siteId) { respond("""{"items":[],"nextCursor":null}""", HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(
                AttachmentListResult.Loaded(
                    ago.chat.android.core.domain.storage
                        .AttachmentListPage(emptyList(), null),
                ),
                api.fetchAttachments(AttachmentListSort.SizeDesc, AttachmentListFilter.None, cursor = null),
            )
        }

    @Test
    fun `a 5xx on the list read is a server error`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.Forbidden) }

            assertEquals(
                AttachmentListResult.Failed(NetworkFailure.ServerError(403)),
                api.fetchAttachments(AttachmentListSort.SizeDesc, AttachmentListFilter.None, cursor = null),
            )
        }

    @Test
    fun `a dropped connection on the list read is NoConnection`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(
                AttachmentListResult.Failed(NetworkFailure.NoConnection),
                api.fetchAttachments(AttachmentListSort.SizeDesc, AttachmentListFilter.None, cursor = null),
            )
        }

    @Test
    fun `an unparseable createdAt on an otherwise-2xx list body is Failed, not an empty page`() =
        runTest {
            val malformed =
                """{"items":[{"id":"a","conversationId":"c","contentType":"image/png","sizeBytes":1,
                    "createdAt":"not-a-date","downloadCount":0,"lastDownloadedAt":null,"senderKind":null,
                    "senderId":null,"isDuplicate":false}],"nextCursor":null}"""
            val api = apiFor(siteId) { respond(malformed, HttpStatusCode.OK, jsonHeaders()) }

            assertEquals(
                AttachmentListResult.Failed(NetworkFailure.Unexpected),
                api.fetchAttachments(AttachmentListSort.SizeDesc, AttachmentListFilter.None, cursor = null),
            )
        }

    // ------------------------------------------------- POST /attachments/bulk-delete

    @Test
    fun `bulk-delete posts the ids and returns the server tally`() =
        runTest {
            var requested: Pair<HttpMethod, String>? = null
            var sentBody: String? = null
            val api =
                apiFor(siteId) { request ->
                    requested = request.method to request.url.toString()
                    sentBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"deletedCount":2,"freedBytes":3000,"notFoundIds":["gone-1"],"alreadyGoneCount":1}""",
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

            val result = api.bulkDelete(listOf("att-1", "att-2"))

            assertEquals(HttpMethod.Post to "$baseUrl/api/v1/sites/$siteId/attachments/bulk-delete", requested)
            val sentIds =
                Json
                    .parseToJsonElement(sentBody!!)
                    .jsonObject
                    .getValue("attachmentIds")
                    .jsonArray
            assertEquals(listOf("att-1", "att-2"), sentIds.map { it.jsonPrimitive.content })
            assertEquals(
                BulkDeleteResult.Deleted(
                    BulkDeleteOutcome(deletedCount = 2, freedBytes = 3000, notFoundIds = listOf("gone-1"), alreadyGoneCount = 1),
                ),
                result,
            )
        }

    @Test
    fun `a 5xx on bulk-delete is a server error, and the list is the caller's to keep`() =
        runTest {
            val api = apiFor(siteId) { respondError(HttpStatusCode.InternalServerError) }

            assertEquals(BulkDeleteResult.Failed(NetworkFailure.ServerError(500)), api.bulkDelete(listOf("att-1")))
        }

    @Test
    fun `a dropped connection on bulk-delete is a transport failure`() =
        runTest {
            val api = apiFor(siteId) { throw IOException("unexpected end of stream") }

            assertEquals(BulkDeleteResult.Failed(NetworkFailure.NoConnection), api.bulkDelete(listOf("att-1")))
        }

    @Test
    fun `no active site on bulk-delete is Unexpected, and never makes a request`() =
        runTest {
            var calls = 0
            val api =
                apiFor(null) {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }

            assertEquals(BulkDeleteResult.Failed(NetworkFailure.Unexpected), api.bulkDelete(listOf("att-1")))
            assertEquals("no active site must never reach the network", 0, calls)
        }

    // ------------------------------------------------------------------------------------------ fixtures

    private fun attachmentPageJson(): String =
        """{"items":[{"id":"att-1","conversationId":"conv-1","contentType":"image/png","sizeBytes":1500,
            "createdAt":"2026-09-01T08:00:00Z","downloadCount":0,"lastDownloadedAt":null,"senderKind":"Visitor",
            "senderId":"s-1","isDuplicate":false}],"nextCursor":{"value":"1500","attachmentId":"att-1"}}"""

    private fun jsonHeaders() = headersOf("Content-Type", "application/json")

    private fun apiFor(
        activeSiteId: String?,
        handler: MockRequestHandler,
    ): KtorSiteAttachmentStorageApi {
        val client =
            HttpClient(MockEngine { request -> handler(request) }) {
                installAgoRestDefaults(
                    accessTokens = MutableAccessTokenProvider(token = "any-token"),
                    activeSite = InMemoryActiveSite(activeSiteId),
                )
            }
        return KtorSiteAttachmentStorageApi(client, baseUrl, InMemoryActiveSite(activeSiteId))
    }
}
