package ago.chat.android.core.network.tags

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.tags.SiteTagsApi
import ago.chat.android.core.domain.tags.Tag
import ago.chat.android.core.domain.tags.TagDeleteResult
import ago.chat.android.core.domain.tags.TagMutationResult
import ago.chat.android.core.domain.tags.TagVocabularyResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-225` (`docs/design/tenant-canned-tags-android.md` §2.3): the adapter behind [SiteTagsApi] — the
 * same "the whole status-code-to-meaning mapping lives here, and only here" shape
 * [ago.chat.android.core.network.cannedresponses.KtorCannedResponsesApi] already establishes.
 *
 * [ActiveSiteSelection.currentSiteId] is read directly here, for all four calls — the identical
 * `{siteId}`-in-URL reason [KtorConversationTagsApi]'s own doc comment gives for its own vocabulary read.
 * A `null` site id is [NetworkFailure.Unexpected] and never reaches the network, for every method.
 *
 * Wire DTOs here are `private` and field-for-field identical to [KtorConversationTagsApi]'s own
 * `TagWireDto`/`TagsResponseWireDto`/`ProblemDetailsWireDto` — the same "every adapter keeps its own
 * un-shared copy" convention that file's own doc comment states, just under a `SiteTag…` prefix: a
 * `private` top-level declaration is file-scoped for *access*, but its name still lives in the shared
 * package namespace, and [KtorConversationTagsApi] already occupies the shorter names in this same
 * `ago.chat.android.core.network.tags` package (unlike [KtorCannedResponsesApi]'s own copy, which sits in
 * a different package and needs no prefix at all).
 */
public class KtorSiteTagsApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : SiteTagsApi {
    /** `GET /api/v1/sites/{siteId}/tags`. */
    override suspend fun fetch(): TagVocabularyResult {
        val siteId = activeSite.currentSiteId() ?: return TagVocabularyResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get(tagsUrl(siteId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return TagVocabularyResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return TagVocabularyResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            TagVocabularyResult.Loaded(response.body<SiteTagsResponseWireDto>().tags.map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty vocabulary" - the identical
            // `KtorConversationTagsApi`/`shapeGuard.ts` lesson, read onto this endpoint.
            TagVocabularyResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `POST /api/v1/sites/{siteId}/tags`, body `{"name"}`. */
    override suspend fun create(name: String): TagMutationResult {
        val siteId = activeSite.currentSiteId() ?: return TagMutationResult.Failed(NetworkFailure.Unexpected)

        return performTagMutation {
            client.post(tagsUrl(siteId)) {
                contentType(ContentType.Application.Json)
                setBody(TagNameRequestWireDto(name))
            }
        }
    }

    /** `PUT /api/v1/sites/{siteId}/tags/{tagId}`, body `{"name"}`. */
    override suspend fun rename(
        tagId: String,
        name: String,
    ): TagMutationResult {
        val siteId = activeSite.currentSiteId() ?: return TagMutationResult.Failed(NetworkFailure.Unexpected)

        return performTagMutation {
            client.put(tagUrl(siteId, tagId)) {
                contentType(ContentType.Application.Json)
                setBody(TagNameRequestWireDto(name))
            }
        }
    }

    /** `DELETE /api/v1/sites/{siteId}/tags/{tagId}`. `204` is [TagDeleteResult.Deleted]; a non-2xx is
     * read for an RFC 7807 `detail` the identical way [performTagMutation] describes for [create]/
     * [rename]. */
    override suspend fun delete(tagId: String): TagDeleteResult {
        val siteId = activeSite.currentSiteId() ?: return TagDeleteResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.delete(tagUrl(siteId, tagId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return TagDeleteResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return TagDeleteResult.Deleted
        }

        val detail = readProblemDetail(response)
        return detail?.let { TagDeleteResult.Refused(it) } ?: TagDeleteResult.Failed(NetworkFailure.ServerError(response.status.value))
    }

    /**
     * [create] and [rename] differ only in HTTP method, path and the presence of a `{tagId}` segment - an
     * argument rather than two near-identical bodies, the same factoring
     * [ago.chat.android.core.network.tags.KtorConversationTagsApi.performTagAction] already uses for its
     * own two identically-shaped writes. A `2xx` is read for the server's own echoed [Tag]
     * ([TagMutationResult.Saved]); a `200` whose body is not that shape is [TagMutationResult.Failed],
     * never a fabricated tag. A non-2xx is read for an RFC 7807 `detail`
     * ([TagMutationResult.Refused]) or, absent one, [TagMutationResult.Failed].
     */
    private suspend fun performTagMutation(request: suspend () -> HttpResponse): TagMutationResult {
        val response =
            try {
                request()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return TagMutationResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                TagMutationResult.Saved(response.body<SiteTagWireDto>().toDomain())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                TagMutationResult.Failed(NetworkFailure.from(failure))
            }
        }

        val detail = readProblemDetail(response)
        return detail?.let { TagMutationResult.Refused(it) } ?: TagMutationResult.Failed(NetworkFailure.ServerError(response.status.value))
    }

    private suspend fun readProblemDetail(response: HttpResponse): String? =
        try {
            response.body<SiteTagProblemDetailsWireDto>().detail
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        }

    private fun tagsUrl(siteId: String): String = "$apiBaseUrl/api/v1/sites/$siteId/tags"

    private fun tagUrl(
        siteId: String,
        tagId: String,
    ): String = "${tagsUrl(siteId)}/$tagId"
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared copy
 * every adapter in this codebase keeps for itself (`docs/design/tenant-canned-tags-android.md` §2.3). */
@Serializable
private data class SiteTagProblemDetailsWireDto(
    val detail: String? = null,
)

/** `TagEndpoints.TagResponseDto`, field for field — [KtorConversationTagsApi]'s own `TagWireDto`
 * restated under this file's own `SiteTag…` prefix (this class's own top-of-file doc comment states why
 * the prefix, not the fields, has to differ). */
@Serializable
private data class SiteTagWireDto(
    val id: String,
    val name: String,
    val createdAt: String,
)

/** `TagEndpoints.TagsResponse`. */
@Serializable
private data class SiteTagsResponseWireDto(
    val tags: List<SiteTagWireDto>,
)

/** The `POST`/`PUT` request body — `{"name"}`, both verbs. */
@Serializable
private data class TagNameRequestWireDto(
    val name: String,
)

private fun SiteTagWireDto.toDomain() = Tag(id = id, name = name, createdAt = createdAt)
