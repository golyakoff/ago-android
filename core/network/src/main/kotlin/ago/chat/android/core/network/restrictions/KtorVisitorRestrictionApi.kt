package ago.chat.android.core.network.restrictions

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.restrictions.VisitorRestrictionActionResult
import ago.chat.android.core.domain.restrictions.VisitorRestrictionApi
import ago.chat.android.core.domain.restrictions.VisitorRestrictionStatusResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-145`: the adapter behind [VisitorRestrictionApi] — the same "the whole status-code-to-meaning
 * mapping lives here, and only here" shape
 * [ago.chat.android.core.network.contactdetails.KtorContactDetailsApi]'s own doc comment states for its
 * sibling slice.
 *
 * `X-Ago-Active-Site` and the bearer token are attached by client plugins (`installAgoRestDefaults`),
 * not threaded through the methods here — the identical reason `KtorContactDetailsApi` gives; all three
 * routes are tenant-scoped by the token/header (the server reads `SiteId` from the operator's own
 * claims, `VisitorRestrictionsEndpoints`' own remarks), not by a `{siteId}` URL segment.
 */
public class KtorVisitorRestrictionApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : VisitorRestrictionApi {
    /** `POST /api/v1/conversations/{conversationId}/block-visitor` — no request body; the `200` body is
     * discarded (see [VisitorRestrictionApi.block]). */
    override suspend fun block(conversationId: String): VisitorRestrictionActionResult =
        write { client.post("$apiBaseUrl/api/v1/conversations/$conversationId/block-visitor") }

    /** `POST /api/v1/visitor-restrictions/{visitorId}/lift` — no request body; `204` on success. */
    override suspend fun lift(visitorId: String): VisitorRestrictionActionResult =
        write { client.post("$apiBaseUrl/api/v1/visitor-restrictions/$visitorId/lift") }

    /**
     * `GET /api/v1/visitor-restrictions`, page by page, until a live restriction for [visitorId] is found
     * or the cursor runs out. A row counts when its `visitorId` matches and `liftedAt` is null — see
     * [VisitorRestrictionApi.isRestricted] for what this membership can and cannot evaluate. Reads the
     * server's own max page size to keep the round-trip count down; follows `nextBeforeId` rather than
     * trusting page one, so an old restriction behind many newer ones is still found.
     */
    override suspend fun isRestricted(visitorId: String): VisitorRestrictionStatusResult {
        var before: String? = null
        while (true) {
            val response =
                try {
                    client.get("$apiBaseUrl/api/v1/visitor-restrictions") {
                        parameter("limit", MAX_PAGE_SIZE)
                        if (before != null) parameter("before", before)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    return VisitorRestrictionStatusResult.Failed(NetworkFailure.from(failure))
                }

            if (!response.status.isSuccess()) {
                return VisitorRestrictionStatusResult.Failed(NetworkFailure.ServerError(response.status.value))
            }

            val page =
                try {
                    response.body<VisitorRestrictionListWireDto>()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // A `200` whose body is not the promised shape is not "no restrictions on file" - the
                    // identical `KtorContactDetailsApi`/`shapeGuard.ts` lesson, read onto this endpoint.
                    return VisitorRestrictionStatusResult.Failed(NetworkFailure.from(failure))
                }

            if (page.items.any { it.visitorId == visitorId && it.liftedAt == null }) {
                return VisitorRestrictionStatusResult.Loaded(restricted = true)
            }

            before = page.nextBeforeId ?: return VisitorRestrictionStatusResult.Loaded(restricted = false)
        }
    }

    /**
     * The shared body of [block] and [lift]: a `2xx` is [VisitorRestrictionActionResult.Succeeded]; a
     * non-`2xx` carrying an RFC 7807 `detail` is a genuine [VisitorRestrictionActionResult.Refused]; every
     * other non-`2xx`, and every transport failure, is [VisitorRestrictionActionResult.Failed] — the
     * identical arm-by-arm mapping
     * [ago.chat.android.core.network.contactdetails.KtorContactDetailsApi.revealContactDetail] makes for
     * its own refusable write, hoisted here because both writes on this port make it the same way.
     */
    private suspend inline fun write(send: () -> HttpResponse): VisitorRestrictionActionResult {
        val response =
            try {
                send()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return VisitorRestrictionActionResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return VisitorRestrictionActionResult.Succeeded
        }

        val detail =
            try {
                response.body<ProblemDetailsWireDto>().detail
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }

        return detail?.let { VisitorRestrictionActionResult.Refused(it) }
            ?: VisitorRestrictionActionResult.Failed(NetworkFailure.ServerError(response.status.value))
    }

    private companion object {
        /** `GetVisitorRestrictionsForSiteHandler.MaxLimit` — read at the server's own ceiling so the
         * cursor follow in [isRestricted] makes as few round-trips as the endpoint allows. */
        const val MAX_PAGE_SIZE = 200
    }
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared copy
 * every adapter in this layer keeps for itself (`KtorContactDetailsApi`'s own `ProblemDetailsWireDto`). */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/** `VisitorRestrictionsEndpoints.VisitorRestrictionListResponse`, reduced to the two fields membership
 * needs — `nextBeforeId` to follow the keyset cursor, and the rows themselves. The rest of the wire
 * response rides along under the client's global `ignoreUnknownKeys`. */
@Serializable
private data class VisitorRestrictionListWireDto(
    // No default: a `200` whose body omits `items` is a dropped shape, not "no restrictions on file" -
    // the identical required-field discipline `KtorContactDetailsApi`'s own `ContactDetailsResponseWireDto`
    // keeps, so [isRestricted]'s shape-guard classifies it [NetworkFailure.Unexpected]. `nextBeforeId`
    // keeps a default because a null cursor is a real, expected value the server sends on the last page.
    val items: List<VisitorRestrictionListItemWireDto>,
    val nextBeforeId: String? = null,
)

/** `VisitorRestrictionsEndpoints.VisitorRestrictionListItemDto`, reduced to what "is this visitor
 * currently restricted" reads — the visitor the row is about and whether it has been lifted. `kind`,
 * `restrictedAt`, `expiresAt` and the rest are on the wire and simply omitted, the same `ignoreUnknownKeys`
 * reduction [ago.chat.android.core.domain.contactdetails.ContactDetail]'s own doc comment explains: no
 * screen on this port reads them. */
@Serializable
private data class VisitorRestrictionListItemWireDto(
    val visitorId: String,
    val liftedAt: String? = null,
)
