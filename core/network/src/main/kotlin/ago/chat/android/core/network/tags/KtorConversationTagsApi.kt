package ago.chat.android.core.network.tags

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.tags.ConversationTag
import ago.chat.android.core.domain.tags.ConversationTagsApi
import ago.chat.android.core.domain.tags.ConversationTagsResult
import ago.chat.android.core.domain.tags.Tag
import ago.chat.android.core.domain.tags.TagActionResult
import ago.chat.android.core.domain.tags.TagVocabularyResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-115`: the adapter behind [ConversationTagsApi] — the same "the whole status-code-to-meaning
 * mapping lives here, and only here" shape [ago.chat.android.core.network.conversations.KtorConversationsApi]'s
 * own doc comment states.
 *
 * [ActiveSiteSelection.currentSiteId] is read directly here, for [fetchSiteTags] alone — the identical
 * reason [ago.chat.android.core.network.team.KtorOperatorTeamApi]'s own doc comment gives for its own
 * `{siteId}`-scoped reads: that endpoint carries the site id in the URL itself, not only in the
 * `X-Ago-Active-Site` header every request already gets from `installAgoRestDefaults`. The two
 * conversation-scoped methods need no site id of their own — the conversation id alone names the
 * resource, the identical shape [ago.chat.android.core.network.contactdetails.KtorContactDetailsApi]
 * already establishes for a sibling per-conversation read.
 */
public class KtorConversationTagsApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : ConversationTagsApi {
    /** `GET /api/v1/sites/{siteId}/tags`. */
    override suspend fun fetchSiteTags(): TagVocabularyResult {
        val siteId = activeSite.currentSiteId() ?: return TagVocabularyResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get("$apiBaseUrl/api/v1/sites/$siteId/tags")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return TagVocabularyResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return TagVocabularyResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            TagVocabularyResult.Loaded(response.body<TagsResponseWireDto>().tags.map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty vocabulary" - the identical
            // `KtorOperatorTeamApi`/`shapeGuard.ts` lesson, read onto this endpoint.
            TagVocabularyResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `GET /api/v1/conversations/{conversationId}/tags`. */
    override suspend fun fetchConversationTags(conversationId: String): ConversationTagsResult {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/conversations/$conversationId/tags")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ConversationTagsResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return ConversationTagsResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            ConversationTagsResult.Loaded(response.body<ConversationTagsResponseWireDto>().tags.map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ConversationTagsResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `POST /api/v1/conversations/{conversationId}/tags/{tagId}` — the first of two identically-shaped
     * writes; see [performTagAction] for the one place their shared shape lives. */
    override suspend fun applyTag(
        conversationId: String,
        tagId: String,
    ): TagActionResult = performTagAction { client.post("$apiBaseUrl/api/v1/conversations/$conversationId/tags/$tagId") }

    /** `DELETE /api/v1/conversations/{conversationId}/tags/{tagId}` — [performTagAction]'s own doc
     * comment covers this too. */
    override suspend fun removeTag(
        conversationId: String,
        tagId: String,
    ): TagActionResult = performTagAction { client.delete("$apiBaseUrl/api/v1/conversations/$conversationId/tags/$tagId") }

    /**
     * `204` is [TagActionResult.Succeeded]; a non-2xx is read for an RFC 7807 `detail` the identical way
     * [ago.chat.android.core.network.conversations.KtorConversationsApi.claim]'s own doc comment
     * describes — a genuine `detail` is [TagActionResult.Refused], shown verbatim, and anything else is
     * [TagActionResult.Failed]. [applyTag]/[removeTag] differ only in which HTTP method and path they
     * send, an argument rather than two near-identical method bodies — the same factoring
     * [ago.chat.android.core.network.bookings.KtorBookingsApi.performBookingAction] already uses for its
     * own three identically-shaped veto writes.
     */
    private suspend fun performTagAction(request: suspend () -> HttpResponse): TagActionResult {
        val response =
            try {
                request()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return TagActionResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return TagActionResult.Succeeded
        }

        val detail =
            try {
                response.body<ProblemDetailsWireDto>().detail
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }

        return detail?.let { TagActionResult.Refused(it) } ?: TagActionResult.Failed(NetworkFailure.ServerError(response.status.value))
    }
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared copy
 * every adapter in this codebase keeps for itself. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/** `TagEndpoints.TagResponseDto`, field for field. */
@Serializable
private data class TagWireDto(
    val id: String,
    val name: String,
    val createdAt: String,
)

private fun TagWireDto.toDomain() = Tag(id = id, name = name, createdAt = createdAt)

/** `TagEndpoints.TagsResponse`. */
@Serializable
private data class TagsResponseWireDto(
    val tags: List<TagWireDto>,
)

/** `TagEndpoints.ConversationTagResponseDto`, field for field. */
@Serializable
private data class ConversationTagWireDto(
    val id: String,
    val name: String,
    val createdAt: String,
    val source: String,
)

private fun ConversationTagWireDto.toDomain() = ConversationTag(id = id, name = name, createdAt = createdAt, source = source)

/** `TagEndpoints.ConversationTagsResponse`. */
@Serializable
private data class ConversationTagsResponseWireDto(
    val tags: List<ConversationTagWireDto>,
)
