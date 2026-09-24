package ago.chat.android.core.network.devices

import ago.chat.android.core.domain.devices.DeviceRegistrationApi
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-06`/`adr/0180`: the adapter behind [DeviceRegistrationApi] - `Ago.Chat.Api.Me.MeDeviceEndpoints`'s
 * two routes, called through the same shared, authenticated `HttpClient` every other `:core:network`
 * adapter uses. `X-Ago-Active-Site` and the bearer token are attached by client plugins
 * (`installAgoRestDefaults`), not threaded through either method here - `KtorConversationsApi`'s own
 * remarks explain why, and it is what makes **"one row per tenancy"** true for free: two calls made
 * while two different sites are active carry two different `X-Ago-Active-Site` headers (proven
 * generically by `ActiveSiteHeaderPluginTest`, not re-proven here) and therefore resolve to two
 * different `operator_id`s server-side, even though [installationId] - deliberately the *same* value
 * both times - never varies. This class has no "which tenancy" concept of its own to get wrong.
 *
 * **`"rustore"`/`"android"` are literals here, not parameters.** `adr/0180` fixed the provider for the
 * whole app, and this is the one client this whole product ships (`docs/architecture.md`'s stack
 * table has no second platform for this repository to build). A port that accepted them as arguments
 * would let a caller send a payload the server was never going to see any other value for - the
 * identical reasoning against inventing a parameter nothing varies.
 */
public class KtorDeviceRegistrationApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : DeviceRegistrationApi {
    override suspend fun register(
        installationId: String,
        token: String,
    ): Boolean {
        val response =
            try {
                client.put("$apiBaseUrl/api/v1/me/devices/$installationId") {
                    // `26-80`'s own `KtorConversationsApi.markRead` doc comment: `ContentNegotiation`
                    // only serializes a body it can match to a registered converter, matched on the
                    // request's own declared `Content-Type` - omitting this is what produced "Kotlin
                    // reflection is not available" the first time this app ever sent a POST body.
                    contentType(ContentType.Application.Json)
                    setBody(RegisterDeviceRequestWireDto(provider = PROVIDER, platform = PLATFORM, token = token))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return false
            }

        return response.status.isSuccess()
    }

    override suspend fun revoke(installationId: String): Boolean {
        val response =
            try {
                client.delete("$apiBaseUrl/api/v1/me/devices/$installationId")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return false
            }

        return response.status.isSuccess()
    }

    private companion object {
        const val PROVIDER = "rustore"
        const val PLATFORM = "android"
    }
}

/** `Ago.Chat.Api.Me.MeDeviceEndpoints.RegisterDeviceRequest` - the three fields that route's own body
 * carries, in the same camelCase the server's default JSON naming policy renders every other DTO in. */
@Serializable
private data class RegisterDeviceRequestWireDto(
    val provider: String,
    val platform: String,
    val token: String,
)
