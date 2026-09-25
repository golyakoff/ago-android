package ago.chat.android.core.network.contactdetails

import ago.chat.android.core.domain.contactdetails.ContactDetail
import ago.chat.android.core.domain.contactdetails.ContactDetailsApi
import ago.chat.android.core.domain.contactdetails.ContactDetailsResult
import ago.chat.android.core.domain.contactdetails.RevealContactDetailResult
import ago.chat.android.core.domain.net.NetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-115`: the adapter behind [ContactDetailsApi] — the same "the whole status-code-to-meaning mapping
 * lives here, and only here" shape [ago.chat.android.core.network.conversations.KtorConversationsApi]'s
 * own doc comment states.
 *
 * `X-Ago-Active-Site` and the bearer token are attached by client plugins (`installAgoRestDefaults`),
 * not threaded through either method here — the identical reason `KtorConversationsApi` gives; the site
 * scope for both endpoints below is carried by the token/header, not by the URL (unlike
 * [ago.chat.android.core.network.tags.KtorConversationTagsApi]'s vocabulary read, which does need an
 * explicit `{siteId}` path segment).
 */
public class KtorContactDetailsApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : ContactDetailsApi {
    /** `GET /api/v1/conversations/{conversationId}/contact-details`. */
    override suspend fun fetchContactDetails(conversationId: String): ContactDetailsResult {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/conversations/$conversationId/contact-details")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ContactDetailsResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return ContactDetailsResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            ContactDetailsResult.Loaded(response.body<ContactDetailsResponseWireDto>().contactDetails.map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "no contact details on file" - the
            // identical `KtorConversationsApi`/`shapeGuard.ts` lesson, read onto this endpoint.
            ContactDetailsResult.Failed(NetworkFailure.from(failure))
        }
    }

    /**
     * `POST /api/v1/conversations/{conversationId}/contact-details/{contactDetailId}/reveal` — no
     * request body. See [ContactDetailsApi.revealContactDetail]'s own doc comment for why no `surface`
     * argument travels here, unlike the calendar's own phone reveal.
     */
    override suspend fun revealContactDetail(
        conversationId: String,
        contactDetailId: String,
    ): RevealContactDetailResult {
        val response =
            try {
                client.post("$apiBaseUrl/api/v1/conversations/$conversationId/contact-details/$contactDetailId/reveal")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return RevealContactDetailResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                RevealContactDetailResult.Revealed(response.body<ContactDetailWireDto>().toDomain())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                RevealContactDetailResult.Failed(NetworkFailure.from(failure))
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

        return detail?.let { RevealContactDetailResult.Refused(it) }
            ?: RevealContactDetailResult.Failed(NetworkFailure.ServerError(response.status.value))
    }
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared copy
 * every adapter in this package keeps for itself
 * ([ago.chat.android.core.network.team.KtorOperatorTeamApi]'s own doc comment states why). */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/** `Ago.Chat.Api.ContactDetails.ContactDetailEndpoints.ContactDetailDto`, reduced to the fields
 * [ContactDetail] carries — see that class's own doc comment for why `recordedByOperatorId`/`source`/
 * `verified`/`recordedAt`/`assessment` are all on the wire and simply omitted here. */
@Serializable
private data class ContactDetailWireDto(
    val id: String,
    val kind: String,
    val value: String,
    val masked: Boolean,
)

private fun ContactDetailWireDto.toDomain() = ContactDetail(id = id, kind = kind, value = value, masked = masked)

/** `Ago.Chat.Api.ContactDetails.ContactDetailEndpoints.ContactDetailsResponse`. */
@Serializable
private data class ContactDetailsResponseWireDto(
    val contactDetails: List<ContactDetailWireDto>,
)
