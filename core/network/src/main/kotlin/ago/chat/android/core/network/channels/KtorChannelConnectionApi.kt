package ago.chat.android.core.network.channels

import ago.chat.android.core.domain.channels.ChannelConnectResult
import ago.chat.android.core.domain.channels.ChannelConnectionApi
import ago.chat.android.core.domain.channels.ChannelDisconnectResult
import ago.chat.android.core.domain.channels.ChannelKind
import ago.chat.android.core.domain.channels.ChannelStatus
import ago.chat.android.core.domain.channels.ChannelStatusResult
import ago.chat.android.core.domain.channels.VkReveal
import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.net.NetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * `26-188`: the adapter behind [ChannelConnectionApi] — the same "the whole status-code-to-meaning
 * mapping lives here, and only here" shape
 * [ago.chat.android.core.network.tags.KtorConversationTagsApi]'s own doc comment states, parameterised
 * by [ChannelKind] rather than copied three times.
 *
 * [ActiveSiteSelection.currentSiteId] is read directly here, for every method — the identical reason
 * [ago.chat.android.core.network.installation.KtorInstallationApi]'s own doc comment gives for its own
 * `{siteId}`-scoped read: all three routes carry the site id in the URL itself, not only in the
 * `X-Ago-Active-Site` header every request already gets from `installAgoRestDefaults`. The bearer token
 * is attached by that same client plugin, never threaded through a method here.
 *
 * **The token never round-trips** (`adr/0069`): [ConnectRequestWireDto] is the only wire shape this
 * file declares with a `token` field, and no response DTO it parses has one — a reconnect is a fresh
 * [connect] call with a fresh token, never a read-then-edit.
 */
public class KtorChannelConnectionApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : ChannelConnectionApi {
    /** `GET /api/v1/sites/{siteId}/channels/{kind.slug}`. */
    override suspend fun fetchStatus(kind: ChannelKind): ChannelStatusResult {
        val siteId = activeSite.currentSiteId() ?: return ChannelStatusResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get(channelUrl(siteId, kind))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ChannelStatusResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return ChannelStatusResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            ChannelStatusResult.Loaded(response.body<ChannelStatusWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape - or whose timestamps do not parse - is not
            // "not connected yet", the identical `KtorConversationTagsApi`/`shapeGuard.ts` lesson read
            // onto this endpoint.
            ChannelStatusResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `POST /api/v1/sites/{siteId}/channels/{kind.slug}` with `{"token": token}`. A `201` carries the
     * shown-once VK reveal, parsed only when [kind] is [ChannelKind.Vk] - every other channel's response
     * has no `callbackUrl`/`webhookSecret` keys to begin with, so [VkReveal] would never build for them
     * regardless. */
    override suspend fun connect(
        kind: ChannelKind,
        token: String,
    ): ChannelConnectResult {
        val siteId = activeSite.currentSiteId() ?: return ChannelConnectResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.post(channelUrl(siteId, kind)) {
                    contentType(ContentType.Application.Json)
                    setBody(ConnectRequestWireDto(token))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ChannelConnectResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return problemDetail(response)?.let { ChannelConnectResult.Refused(it) }
                ?: ChannelConnectResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            val body = response.body<ConnectResponseWireDto>()
            val reveal =
                if (kind == ChannelKind.Vk && body.callbackUrl != null && body.webhookSecret != null) {
                    VkReveal(callbackUrl = body.callbackUrl, webhookSecret = body.webhookSecret)
                } else {
                    null
                }
            ChannelConnectResult.Connected(reveal)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ChannelConnectResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `DELETE /api/v1/sites/{siteId}/channels/{kind.slug}/{channelCredentialId}`. */
    override suspend fun disconnect(
        kind: ChannelKind,
        channelCredentialId: String,
    ): ChannelDisconnectResult {
        val siteId = activeSite.currentSiteId() ?: return ChannelDisconnectResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.delete("${channelUrl(siteId, kind)}/$channelCredentialId")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ChannelDisconnectResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return ChannelDisconnectResult.Disconnected
        }

        return problemDetail(response)?.let { ChannelDisconnectResult.Refused(it) }
            ?: ChannelDisconnectResult.Failed(NetworkFailure.ServerError(response.status.value))
    }

    private fun channelUrl(
        siteId: String,
        kind: ChannelKind,
    ): String = "$apiBaseUrl/api/v1/sites/$siteId/channels/${kind.slug}"

    /**
     * A non-2xx response read for an RFC 7807 `detail` — the identical shape
     * [ago.chat.android.core.network.tags.KtorConversationTagsApi.performTagAction]'s own doc comment
     * describes. Returns `null` when no genuine `detail` could be read, leaving the caller to fall back
     * to [NetworkFailure.ServerError].
     */
    private suspend fun problemDetail(response: HttpResponse): String? =
        try {
            response.body<ProblemDetailsWireDto>().detail
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            null
        }
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared copy
 * every adapter in this codebase keeps for itself. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

/** The status response, byte-identical across all three channels
 * (`TelegramChannelStatusResponse`/`MaxChannelStatusResponse`/`VkChannelStatusResponse`). */
@Serializable
private data class ChannelStatusWireDto(
    val connected: Boolean,
    val channelCredentialId: String? = null,
    val createdAt: String? = null,
    val verified: Boolean? = null,
    val unreachable: Boolean = false,
    val refusalReason: String? = null,
    val checkedAt: String,
)

private fun ChannelStatusWireDto.toDomain(): ChannelStatus =
    ChannelStatus(
        connected = connected,
        channelCredentialId = channelCredentialId,
        createdAt = createdAt?.let(Instant::parse),
        verified = verified,
        unreachable = unreachable,
        refusalReason = refusalReason,
        checkedAt = Instant.parse(checkedAt),
    )

@Serializable
private data class ConnectRequestWireDto(
    val token: String,
)

/** The connect response — `channelCredentialId`/`createdAt` are required (a `201` without them is a
 * shape mismatch, not a connect this port can make sense of), while `callbackUrl`/`webhookSecret` exist
 * only on VK's own response and default to `null` everywhere else. */
@Serializable
private data class ConnectResponseWireDto(
    val channelCredentialId: String,
    val createdAt: String,
    val callbackUrl: String? = null,
    val webhookSecret: String? = null,
)
