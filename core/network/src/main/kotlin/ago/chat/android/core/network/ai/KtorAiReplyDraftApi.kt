package ago.chat.android.core.network.ai

import ago.chat.android.core.domain.ai.AiReplyDraftApi
import ago.chat.android.core.domain.ai.AiReplyDraftStatus
import ago.chat.android.core.domain.ai.AiReplyDraftStatusResult
import ago.chat.android.core.domain.ai.AiReplyDraftWriteResult
import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.net.NetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-246` (`ago-console`'s own `aiAddOnApi.ts`): the adapter behind [AiReplyDraftApi] — the same "the
 * whole status-code-to-meaning mapping lives here, and only here" shape
 * [ago.chat.android.core.network.autoreply.KtorOfflineAutoReplyApi] already establishes.
 *
 * [ActiveSiteSelection.currentSiteId] is read directly here, for both [fetch] and [setEnabled] — the
 * identical reason that adapter's own doc comment gives: `{siteId}` is in the URL itself, not only in the
 * `X-Ago-Active-Site` header every request already gets from `installAgoRestDefaults`. The bearer token
 * is attached by that same client plugin, never threaded through either method here.
 *
 * **[setEnabled] sends an empty JSON object body**, mirroring the console's own `POST` with a `{}` body
 * and `Content-Type: application/json` — the `enable`/`disable` routes take no fields, but the console
 * (and this adapter) send `{}` rather than an empty body so the server's JSON body reader never sees a
 * zero-length payload.
 */
public class KtorAiReplyDraftApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : AiReplyDraftApi {
    /** `GET /api/v1/sites/{siteId}/ai-add-on`. */
    override suspend fun fetch(): AiReplyDraftStatusResult {
        val siteId = activeSite.currentSiteId() ?: return AiReplyDraftStatusResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get(aiAddOnUrl(siteId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return AiReplyDraftStatusResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            // Reachable only for an operator this app already believes holds `site:configure`
            // (`MoreScreen`'s own gate), so "could not load, try again" is the honest thing to say — the
            // identical reasoning `KtorOfflineAutoReplyApi`'s own doc comment records for its own read.
            return AiReplyDraftStatusResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            AiReplyDraftStatusResult.Loaded(response.body<AiAddOnStatusWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "the add-on is off" — the identical
            // `shapeGuard.ts`/`KtorOfflineAutoReplyApi` lesson, read onto this endpoint.
            AiReplyDraftStatusResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `POST /api/v1/sites/{siteId}/ai-add-on/{enable|disable}`, empty JSON body. */
    override suspend fun setEnabled(enabled: Boolean): AiReplyDraftWriteResult {
        val siteId = activeSite.currentSiteId() ?: return AiReplyDraftWriteResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post(aiAddOnUrl(siteId, if (enabled) "/enable" else "/disable")) {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return AiReplyDraftWriteResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return AiReplyDraftWriteResult.Saved
        }

        val detail =
            try {
                response.body<ProblemDetailsWireDto>().detail
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }

        return detail?.let { AiReplyDraftWriteResult.Refused(it) }
            ?: AiReplyDraftWriteResult.Failed(NetworkFailure.ServerError(response.status.value))
    }

    private fun aiAddOnUrl(
        siteId: String,
        suffix: String = "",
    ): String = "$apiBaseUrl/api/v1/sites/$siteId/ai-add-on$suffix"
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared copy
 * every adapter in this repository keeps for itself
 * ([ago.chat.android.core.network.autoreply.KtorOfflineAutoReplyApi] states why). */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/**
 * `AiAddOnEndpoints.AiAddOnStatusResponse`'s wire shape (`ago-chat`), read for only the five fields this
 * door needs. The response carries more (`documentKey`, `currentVersion`, `currentTitle`, `currentBody`,
 * `acceptedAt`, `declaredBy`), all ignored here — `installAgoRestDefaults` configures the JSON reader to
 * ignore unknown keys, so declaring only what is read keeps this DTO to the availability decision the
 * screen actually makes.
 *
 * The two legal facts arrive as `acceptedVersion`/`declaredAt` (each `null` until made); [toDomain]
 * collapses each to the boolean the console derives (`accepted = acceptedVersion !== null`,
 * `declared = declaredAt !== null`). Both, and `effectiveFrom`, are decode-only and nullable, so a
 * default of `null` is safe — this DTO never builds an outgoing body (both `POST`s send `{}`).
 */
@Serializable
private data class AiAddOnStatusWireDto(
    val purchased: Boolean,
    val enabled: Boolean,
    val effectiveFrom: String? = null,
    val acceptedVersion: String? = null,
    val declaredAt: String? = null,
)

private fun AiAddOnStatusWireDto.toDomain() =
    AiReplyDraftStatus(
        purchased = purchased,
        enabled = enabled,
        effectiveFrom = effectiveFrom,
        agreementAccepted = acceptedVersion != null,
        basisDeclared = declaredAt != null,
    )
