package ago.chat.android.core.network.cannedresponses

import ago.chat.android.core.domain.cannedresponses.CannedResponse
import ago.chat.android.core.domain.cannedresponses.CannedResponsesApi
import ago.chat.android.core.domain.cannedresponses.CannedResponsesResult
import ago.chat.android.core.domain.cannedresponses.CannedResponsesWriteResult
import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.net.NetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-220` (`docs/design/tenant-canned-tags-android.md` §1.3): the adapter behind [CannedResponsesApi] —
 * the same "the whole status-code-to-meaning mapping lives here, and only here" shape
 * [ago.chat.android.core.network.autoreply.KtorOfflineAutoReplyApi] already establishes.
 *
 * [ActiveSiteSelection.currentSiteId] is read directly here, for both [fetch] and [save] — the identical
 * reason [KtorOfflineAutoReplyApi][ago.chat.android.core.network.autoreply.KtorOfflineAutoReplyApi]'s own
 * doc comment gives for its own `{siteId}`-scoped routes: `{siteId}` is in the URL itself, not only in
 * the `X-Ago-Active-Site` header every request already gets from `installAgoRestDefaults`. The bearer
 * token is attached by that same client plugin, never threaded through either method here.
 *
 * **[save] serialises the whole library on every call**, [responses] included in the exact order the
 * caller handed it — there is no partial-update overload on [CannedResponsesApi], and
 * [CannedResponsesWireDto] declares no defaults for the identical reason
 * [ago.chat.android.core.network.autoreply.KtorOfflineAutoReplyApi]'s own `OfflineAutoReplyWireDto` doc
 * comment states: this DTO also builds the outgoing `PUT` body, so every field stays a required
 * constructor parameter and `kotlinx.serialization` always encodes it regardless of value or of any
 * future `agoJson` `encodeDefaults` change.
 */
public class KtorCannedResponsesApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : CannedResponsesApi {
    /** `GET /api/v1/sites/{siteId}/canned-responses`. */
    override suspend fun fetch(): CannedResponsesResult {
        val siteId = activeSite.currentSiteId() ?: return CannedResponsesResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get(cannedResponsesUrl(siteId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return CannedResponsesResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            // Reachable only for an operator this app already believes holds `site:configure`
            // (`MoreScreen`'s own gate), so "could not load, try again" is the honest thing to say - the
            // identical reasoning `KtorOfflineAutoReplyApi`'s own doc comment records for its own
            // sibling read.
            return CannedResponsesResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            CannedResponsesResult.Loaded(response.body<CannedResponsesWireDto>().responses.map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty library" - the identical
            // `KtorConversationTagsApi`/`shapeGuard.ts` lesson, read onto this endpoint.
            CannedResponsesResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `PUT /api/v1/sites/{siteId}/canned-responses`, body = the complete [responses]. */
    override suspend fun save(responses: List<CannedResponse>): CannedResponsesWriteResult {
        val siteId = activeSite.currentSiteId() ?: return CannedResponsesWriteResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.put(cannedResponsesUrl(siteId)) {
                    contentType(ContentType.Application.Json)
                    setBody(CannedResponsesWireDto(responses.map { it.toWireDto() }))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return CannedResponsesWriteResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                CannedResponsesWriteResult.Saved(response.body<CannedResponsesWireDto>().responses.map { it.toDomain() })
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                CannedResponsesWriteResult.Failed(NetworkFailure.from(failure))
            }
        }

        val detail =
            try {
                response.body<ProblemDetailsWireDto>().detail
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }

        return detail?.let { CannedResponsesWriteResult.Refused(it) }
            ?: CannedResponsesWriteResult.Failed(NetworkFailure.ServerError(response.status.value))
    }

    private fun cannedResponsesUrl(siteId: String): String = "$apiBaseUrl/api/v1/sites/$siteId/canned-responses"
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared copy
 * every adapter in this codebase keeps for itself
 * ([ago.chat.android.core.network.team.KtorOperatorTeamApi]'s own doc comment states why). */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/** One entry, field for field (`docs/design/tenant-canned-tags-android.md` §1.3) — no `id`, matching
 * [CannedResponse] itself. */
@Serializable
private data class CannedResponseItemWireDto(
    val title: String,
    val body: String,
)

/**
 * `CannedResponseEndpoints`' `GET`/`PUT` shape — one private DTO for both the `PUT` body and every `2xx`
 * echo (`GET` and `PUT`), the identical "one DTO, both directions" shape
 * [ago.chat.android.core.network.autoreply.KtorOfflineAutoReplyApi]'s own `OfflineAutoReplyWireDto`
 * already establishes.
 */
@Serializable
private data class CannedResponsesWireDto(
    val responses: List<CannedResponseItemWireDto>,
)

private fun CannedResponseItemWireDto.toDomain() = CannedResponse(title = title, body = body)

private fun CannedResponse.toWireDto() = CannedResponseItemWireDto(title = title, body = body)
