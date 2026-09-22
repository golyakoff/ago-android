package ago.chat.android.core.network.permissions

import ago.chat.android.core.domain.identity.ProbeFailure
import ago.chat.android.core.domain.permissions.OperatorPermissionsApi
import ago.chat.android.core.domain.permissions.PermissionsFetch
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-16`: the adapter behind [OperatorPermissionsApi] — `GET /api/v1/operators/me`, decoded for its
 * body this time rather than its status alone
 * ([ago.chat.android.core.network.identity.KtorIdentityApi.probeOperatorSeat] is the status-only
 * reading of the identical endpoint; [OperatorPermissionsApi]'s own doc comment says why this is a
 * second call rather than a shared one).
 *
 * Decodes only [OperatorPermissionsResponseDto.permissions] — `agoJson`'s own `ignoreUnknownKeys`
 * (`AgoHttpClient.kt`) means the response's `operatorId`/`siteId`/`locale`/`enabledModules`/
 * `credentialsArePublished` fields (`ago-console/src/api/operatorsApi.ts`'s own
 * `OperatorPermissionsResponse`) are simply never read here rather than needing to be declared and
 * discarded — nothing on this class's own signature asks for them, and adding a reader for one is a
 * one-line change whenever a caller actually needs it.
 */
public class KtorOperatorPermissionsApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : OperatorPermissionsApi {
    override suspend fun fetchMyPermissions(): PermissionsFetch {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/operators/me")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return PermissionsFetch.Failed(ProbeFailure.Transport(failure.describe()))
            }

        if (!response.status.isSuccess()) {
            return PermissionsFetch.Failed(ProbeFailure.UnexpectedStatus(response.status.value))
        }

        return try {
            val body = response.body<OperatorPermissionsResponseDto>()
            PermissionsFetch.Loaded(body.permissions.toSet())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body has no readable `permissions` array is not "holds nothing" - the
            // identical `KtorIdentityApi.listMyTenancies` lesson (`ago-console`'s `shapeGuard.ts`):
            // a dropped array must never look like a real, empty answer.
            PermissionsFetch.Failed(ProbeFailure.Malformed(failure.describe()))
        }
    }
}

private fun Exception.describe(): String = "${this::class.simpleName}: ${message ?: "no detail"}"

/** `Ago.Chat.Contracts.OperatorPermissionsResponse`, camelCase per ASP.NET Core's default policy -
 * only the one field this class actually reads. */
@Serializable
private data class OperatorPermissionsResponseDto(
    val permissions: List<String>,
)
