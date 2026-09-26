package ago.chat.android.core.network.contactdetails

import ago.chat.android.core.domain.contactdetails.ContactDetail
import ago.chat.android.core.domain.contactdetails.ContactDetailWriteResult
import ago.chat.android.core.domain.contactdetails.ContactDetailsApi
import ago.chat.android.core.domain.contactdetails.ContactDetailsResult
import ago.chat.android.core.domain.contactdetails.RevealContactDetailResult
import ago.chat.android.core.domain.net.NetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-115`: the adapter behind [ContactDetailsApi] — the same "the whole status-code-to-meaning mapping
 * lives here, and only here" shape [ago.chat.android.core.network.conversations.KtorConversationsApi]'s
 * own doc comment states.
 *
 * `26-167`: [editContactDetail] and [setContactDetailAssessment] join the same adapter — confirmed
 * against `ago-chat` `8530e22`'s `ContactDetailEndpoints.HandleEditAsync`/`HandleSetAssessmentAsync`,
 * which both echo the identical `ContactDetailDto` shape the list/reveal reads already use, so this file
 * keeps one private [ContactDetailWireDto] for every 2xx response rather than one per endpoint.
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

    /** `PATCH /api/v1/conversations/{conversationId}/contact-details/{contactDetailId}` `{value}`. See
     * [ContactDetailsApi.editContactDetail]'s own doc comment for the assessment-reset behaviour this
     * adapter does not itself need to know about — it just returns whatever row the server sends back. */
    override suspend fun editContactDetail(
        conversationId: String,
        contactDetailId: String,
        value: String,
    ): ContactDetailWriteResult =
        write {
            client.patch("$apiBaseUrl/api/v1/conversations/$conversationId/contact-details/$contactDetailId") {
                contentType(ContentType.Application.Json)
                setBody(EditContactDetailRequestWireDto(value))
            }
        }

    /** `PATCH /api/v1/conversations/{conversationId}/contact-details/{contactDetailId}/assessment`
     * `{assessment}`. */
    override suspend fun setContactDetailAssessment(
        conversationId: String,
        contactDetailId: String,
        assessment: String,
    ): ContactDetailWriteResult =
        write {
            client.patch(
                "$apiBaseUrl/api/v1/conversations/$conversationId/contact-details/$contactDetailId/assessment",
            ) {
                contentType(ContentType.Application.Json)
                setBody(SetContactDetailAssessmentRequestWireDto(assessment))
            }
        }

    /**
     * The one place [editContactDetail] and [setContactDetailAssessment]'s shared shape lives — they
     * differ only in path and request body, and both answer the identical `2xx`-row /
     * non-2xx-`detail` / transport-failure split [revealContactDetail] above already implements, so this
     * is that same three-way classification lifted out rather than copied twice.
     */
    private suspend fun write(request: suspend () -> HttpResponse): ContactDetailWriteResult {
        val response =
            try {
                request()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ContactDetailWriteResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                ContactDetailWriteResult.Updated(response.body<ContactDetailWireDto>().toDomain())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                ContactDetailWriteResult.Failed(NetworkFailure.from(failure))
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

        return detail?.let { ContactDetailWriteResult.Refused(it) }
            ?: ContactDetailWriteResult.Failed(NetworkFailure.ServerError(response.status.value))
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
 * `verified`/`recordedAt` are on the wire and simply omitted here.
 *
 * `26-167`: [assessment] defaults to `"Unset"` rather than being required — the server always sends it
 * (`ContactDetailDto.Assessment`), but a default here means a response shape from before this field
 * existed still parses instead of failing decode, the same protective reasoning [ContactDetail.assessment]
 * itself carries. */
@Serializable
private data class ContactDetailWireDto(
    val id: String,
    val kind: String,
    val value: String,
    val masked: Boolean,
    val assessment: String = "Unset",
)

private fun ContactDetailWireDto.toDomain() = ContactDetail(id = id, kind = kind, value = value, masked = masked, assessment = assessment)

/** `Ago.Chat.Api.ContactDetails.ContactDetailEndpoints.ContactDetailsResponse`. */
@Serializable
private data class ContactDetailsResponseWireDto(
    val contactDetails: List<ContactDetailWireDto>,
)

/** `Ago.Chat.Api.ContactDetails.ContactDetailEndpoints.EditContactDetailRequest`. */
@Serializable
private data class EditContactDetailRequestWireDto(
    val value: String,
)

/** `Ago.Chat.Api.ContactDetails.ContactDetailEndpoints.SetContactDetailAssessmentRequest`. */
@Serializable
private data class SetContactDetailAssessmentRequestWireDto(
    val assessment: String,
)
