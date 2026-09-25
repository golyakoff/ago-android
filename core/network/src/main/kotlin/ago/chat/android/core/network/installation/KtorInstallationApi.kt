package ago.chat.android.core.network.installation

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.installation.InstallationApi
import ago.chat.android.core.domain.installation.SiteInstallation
import ago.chat.android.core.domain.installation.SiteInstallationResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `26-159`: the adapter behind [InstallationApi] — the same "the whole status-code-to-meaning mapping
 * lives here, and only here" shape
 * [ago.chat.android.core.network.tags.KtorConversationTagsApi] and
 * [ago.chat.android.core.network.analytics.KtorSiteAnalyticsApi] already establish. No Ktor type crosses
 * back over the port: the one `@Serializable` class below is `private` to this file, and the only thing
 * it hands out is a `:core:domain` value.
 *
 * [ActiveSiteSelection.currentSiteId] is read directly here, for [fetchInstallation] alone — the
 * identical reason [ago.chat.android.core.network.tags.KtorConversationTagsApi]'s own doc comment gives
 * for its `{siteId}`-scoped read: this endpoint carries the site id in the URL itself, not only in the
 * `X-Ago-Active-Site` header every request already gets from `installAgoRestDefaults`. The bearer token
 * is attached by the same client plugin, never threaded through this method.
 *
 * **Never a raw exception class name or a hostname on screen** (`26-59`): [classify] answers only "no
 * network" or "something else", by [Exception] type, never by printing `this::class.simpleName` or the
 * exception's own `message` (`UnknownHostException`'s message quotes the host it failed to resolve).
 */
public class KtorInstallationApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : InstallationApi {
    /** `GET /api/v1/sites/{siteId}/installation`. */
    override suspend fun fetchInstallation(): SiteInstallationResult {
        val siteId = activeSite.currentSiteId() ?: return SiteInstallationResult.Failed(BookingsQueueFailure.Unexpected)

        val response =
            try {
                client.get("$apiBaseUrl/api/v1/sites/$siteId/installation")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return SiteInstallationResult.Failed(classify(failure))
            }

        if (!response.status.isSuccess()) {
            // A refusal here is either a genuinely stale permission set or a server-side disagreement -
            // this screen is only reachable for an operator the app already believes holds `site:configure`
            // (`MoreScreen`'s own gate), so "could not load, try again" is the honest thing to say, the
            // identical reasoning [ago.chat.android.core.network.analytics.KtorSiteAnalyticsApi] records
            // for its own `Conversation.Forbidden`.
            return SiteInstallationResult.Failed(BookingsQueueFailure.Unexpected)
        }

        return try {
            SiteInstallationResult.Loaded(response.body<SiteInstallationWireDto>().toDomain())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "an empty installation" - the identical
            // `KtorConversationTagsApi`/`shapeGuard.ts` lesson, read onto this endpoint.
            SiteInstallationResult.Failed(classify(failure))
        }
    }
}

/**
 * `Ago.Chat.Api.Sites.SiteInstallationEndpoints.SiteInstallationResponse`, reduced to the two fields this
 * screen draws - `agoJson`'s own `ignoreUnknownKeys` (`AgoHttpClient.kt`) is what lets this omit the
 * install-state timestamps, `usedRecently`, `state` and the funnel rather than declare and discard them,
 * the same "no name invented for a screen with no use for it yet" discipline every wire DTO in this app
 * follows. `allowedOrigins` defaults to empty: a site with none yet is a real, drawable state (an empty
 * list the screen renders as "none set"), never a parse failure.
 */
@Serializable
private data class SiteInstallationWireDto(
    val publicKey: String,
    val allowedOrigins: List<String> = emptyList(),
)

private fun SiteInstallationWireDto.toDomain() = SiteInstallation(publicKey = publicKey, allowedOrigins = allowedOrigins)

/** See [KtorInstallationApi]'s own class-level doc comment. An [IOException] (no route, DNS failure, a
 * dropped socket, a timeout) is the one thing worth calling "no network"; a shape mismatch on an
 * otherwise-successful response is genuinely a different problem and falls into
 * [BookingsQueueFailure.Unexpected]. The identical `classify` every read adapter in this app keeps
 * private to its own file. */
private fun classify(failure: Exception): BookingsQueueFailure =
    if (failure is IOException) BookingsQueueFailure.Transport else BookingsQueueFailure.Unexpected
