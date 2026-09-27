package ago.chat.android.core.network.modules

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.modules.EnabledModule
import ago.chat.android.core.domain.modules.ModulesApi
import ago.chat.android.core.domain.modules.ModulesResult
import ago.chat.android.core.domain.net.NetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

/**
 * `26-199`/`M1`: the adapter behind [ModulesApi] — the same "the whole status-code-to-meaning mapping
 * lives here, and only here" shape [ago.chat.android.core.network.tags.KtorSiteTagsApi]'s own doc
 * comment states for its sibling `{siteId}`-scoped read.
 *
 * [ActiveSiteSelection.currentSiteId] is read directly here, the identical `{siteId}`-in-URL reason
 * [ago.chat.android.core.network.tags.KtorSiteTagsApi]'s own doc comment gives for its own vocabulary
 * read — this endpoint carries the site id in the URL itself, not only in the `X-Ago-Active-Site` header
 * every request already gets from `installAgoRestDefaults`. A `null` site id is
 * [NetworkFailure.Unexpected] and never reaches the network, the same guard that adapter's own [fetch]
 * makes.
 */
public class KtorModulesApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
    private val activeSite: ActiveSiteSelection,
) : ModulesApi {
    /** `GET /api/v1/sites/{siteId}/modules`. */
    override suspend fun fetch(): ModulesResult {
        val siteId = activeSite.currentSiteId() ?: return ModulesResult.Failed(NetworkFailure.Unexpected)

        val response =
            try {
                client.get("$apiBaseUrl/api/v1/sites/$siteId/modules")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ModulesResult.Failed(NetworkFailure.from(failure))
            }

        if (!response.status.isSuccess()) {
            return ModulesResult.Failed(NetworkFailure.ServerError(response.status.value))
        }

        return try {
            ModulesResult.Loaded(response.body<EnabledModulesResponseWireDto>().modules.map { it.toDomain() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "no modules on this account" - the
            // identical `KtorSiteTagsApi`/`shapeGuard.ts` lesson, read onto this endpoint.
            ModulesResult.Failed(NetworkFailure.from(failure))
        }
    }
}

/**
 * `Ago.Chat.Api.Modules.ModuleEndpoints.EnableModuleResponse`, field for field — `moduleKey`,
 * `triggerWords`, `entryPoint` carry no default (a row missing any of them fails the whole page's
 * deserialization rather than silently drawing a blank module, the identical required-field discipline
 * [ago.chat.android.core.network.restrictions.KtorVisitorRestrictionApi]'s own row DTO states);
 * `grantedByOwner`/`expiresAt` mirror the wire's own `= false`/`= null` defaults.
 */
@Serializable
private data class EnableModuleResponseWireDto(
    val moduleKey: String,
    val triggerWords: List<String>,
    val entryPoint: String,
    val grantedByOwner: Boolean = false,
    val expiresAt: String? = null,
)

/** `Ago.Chat.Api.Modules.ModuleEndpoints.EnabledModulesResponse`. */
@Serializable
private data class EnabledModulesResponseWireDto(
    val modules: List<EnableModuleResponseWireDto>,
)

private fun EnableModuleResponseWireDto.toDomain(): EnabledModule =
    EnabledModule(
        moduleKey = moduleKey,
        triggerWords = triggerWords,
        entryPoint = entryPoint,
        grantedByOwner = grantedByOwner,
        expiresAt = expiresAt?.toRequiredInstant(),
    )

/** Unlike a `toInstantOrNull`-shaped helper, this one *throws* on a malformed value rather than
 * degrading to `null` — the identical
 * [ago.chat.android.core.network.restrictions.KtorVisitorRestrictionApi]'s own `toRequiredInstant`
 * reasoning: an [expiresAt] that is present but unparseable is a dropped shape, not "does not expire",
 * and the outer `catch` in [KtorModulesApi.fetch] is what turns this throw into the honest
 * `Failed(Unexpected)` the shape-guard discipline asks for. Each adapter keeps its own un-shared copy of
 * this helper, the same convention [ago.chat.android.core.network.tags.KtorSiteTagsApi]'s own doc
 * comment states for its wire DTOs. */
private fun String.toRequiredInstant(): Instant = OffsetDateTime.parse(this).toInstant()
