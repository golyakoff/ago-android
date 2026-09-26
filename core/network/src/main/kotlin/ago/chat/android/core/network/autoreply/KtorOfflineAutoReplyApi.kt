package ago.chat.android.core.network.autoreply

import ago.chat.android.core.domain.autoreply.AutoReplyRule
import ago.chat.android.core.domain.autoreply.OfflineAutoReply
import ago.chat.android.core.domain.autoreply.OfflineAutoReplyApi
import ago.chat.android.core.domain.autoreply.OfflineAutoReplyResult
import ago.chat.android.core.domain.autoreply.OfflineAutoReplyWriteResult
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
 * `26-192`/`C5` (`docs/design/tenant-channels-android.md` §4.2): the adapter behind
 * [OfflineAutoReplyApi] — the same "the whole status-code-to-meaning mapping lives here, and only here"
 * shape [ago.chat.android.core.network.widgetconfig.KtorWidgetConfigApi] already establishes.
 *
 * [ActiveSiteSelection.currentSiteId] is read directly here, for both [fetch] and [update] — the
 * identical reason [KtorWidgetConfigApi]'s own doc comment gives for its own `{siteId}`-scoped routes:
 * `{siteId}` is in the URL itself, not only in the `X-Ago-Active-Site` header every request already gets
 * from `installAgoRestDefaults`. The bearer token is attached by that same client plugin, never threaded
 * through either method here.
 *
 * **[update] serialises the whole [OfflineAutoReply] on every call**, [rules] included in the exact
 * order the caller handed it — there is no partial-update overload on [OfflineAutoReplyApi], and
 * [OfflineAutoReplyWireDto] declares no defaults for the identical reason
 * [ago.chat.android.core.network.widgetconfig.WidgetConfigWireDto]'s own doc comment states: this DTO
 * also builds the outgoing `PUT` body, so every field stays a required constructor parameter and
 * `kotlinx.serialization` always encodes it regardless of value or of any future `agoJson`
 * `encodeDefaults` change.
 */
public class KtorOfflineAutoReplyApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : OfflineAutoReplyApi {
    /** `GET /api/v1/sites/{siteId}/offline-auto-reply`. */
    override suspend fun fetch(): OfflineAutoReplyResult {
        val siteId = activeSite.currentSiteId() ?: return OfflineAutoReplyResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get(offlineAutoReplyUrl(siteId))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return OfflineAutoReplyResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            // Reachable only for an operator this app already believes holds `site:configure`
            // (`MoreScreen`'s own gate), so "could not load, try again" is the honest thing to say - the
            // identical reasoning `KtorWidgetConfigApi`'s own doc comment records for its own sibling read.
            return OfflineAutoReplyResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            OfflineAutoReplyResult.Loaded(response.body<OfflineAutoReplyWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "auto-reply disabled with no rules" -
            // the identical `KtorConversationTagsApi`/`shapeGuard.ts` lesson, read onto this endpoint.
            OfflineAutoReplyResult.Failed(NetworkFailure.from(failure))
        }
    }

    /** `PUT /api/v1/sites/{siteId}/offline-auto-reply`, body = the complete [settings]. */
    override suspend fun update(settings: OfflineAutoReply): OfflineAutoReplyWriteResult {
        val siteId = activeSite.currentSiteId() ?: return OfflineAutoReplyWriteResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.put(offlineAutoReplyUrl(siteId)) {
                    contentType(ContentType.Application.Json)
                    setBody(settings.toWireDto())
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return OfflineAutoReplyWriteResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            return try {
                OfflineAutoReplyWriteResult.Saved(response.body<OfflineAutoReplyWireDto>().toDomain())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                OfflineAutoReplyWriteResult.Failed(NetworkFailure.from(failure))
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

        return detail?.let { OfflineAutoReplyWriteResult.Refused(it) }
            ?: OfflineAutoReplyWriteResult.Failed(NetworkFailure.ServerError(response.status.value))
    }

    private fun offlineAutoReplyUrl(siteId: String): String = "$apiBaseUrl/api/v1/sites/$siteId/offline-auto-reply"
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared copy
 * every adapter in this package keeps for itself
 * ([ago.chat.android.core.network.team.KtorOperatorTeamApi]'s own doc comment states why). */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)

@Serializable
private data class AutoReplyRuleWireDto(
    val keyword: String,
    val reply: String,
)

/**
 * `OfflineAutoReplyEndpoints`' `GET`/`PUT` shape, field for field (`docs/design/tenant-channels-android.md`
 * §4.1) — one private DTO for both the `PUT` body and every `2xx` echo (`GET` and `PUT`), the identical
 * "one DTO, both directions" shape [ago.chat.android.core.network.widgetconfig.WidgetConfigWireDto]
 * already establishes.
 *
 * **Deliberately no default value on any field**, for the identical reason that DTO's own doc comment
 * states for itself: this DTO also builds the outgoing `PUT` body, so a default risks `agoJson` omitting
 * an at-default field from the outgoing JSON if this app's serializer configuration ever turns
 * `encodeDefaults` off. Declaring no defaults means `enabled`/`fallbackReply`/`rules` are all required
 * constructor parameters, always encoded regardless of value. Safe on the decode side too: the server
 * always sends all three fields (`docs/design/tenant-channels-android.md` §4.1), so there is no legacy-row
 * case here needing a resilience default.
 */
@Serializable
private data class OfflineAutoReplyWireDto(
    val enabled: Boolean,
    val fallbackReply: String,
    val rules: List<AutoReplyRuleWireDto>,
)

private fun OfflineAutoReplyWireDto.toDomain() =
    OfflineAutoReply(
        enabled = enabled,
        fallbackReply = fallbackReply,
        rules = rules.map { AutoReplyRule(keyword = it.keyword, reply = it.reply) },
    )

private fun OfflineAutoReply.toWireDto() =
    OfflineAutoReplyWireDto(
        enabled = enabled,
        fallbackReply = fallbackReply,
        rules = rules.map { AutoReplyRuleWireDto(keyword = it.keyword, reply = it.reply) },
    )
