package ago.chat.android.core.network.accountdeletion

import ago.chat.android.core.domain.accountdeletion.AccountDeletionApi
import ago.chat.android.core.domain.accountdeletion.EraseAccountResult
import ago.chat.android.core.domain.net.NetworkFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-252` (`ago-console`'s own `sitesApi.ts` `eraseSite`): the adapter behind [AccountDeletionApi] — the
 * same "the whole status-code-to-meaning mapping lives here, and only here" shape
 * [ago.chat.android.core.network.siteexport.KtorSiteExportApi] already establishes.
 *
 * **No `ActiveSiteSelection` and no `{siteId}` in the URL**, unlike every other site-scoped adapter in this
 * module: the console `eraseSite` posts to a bare `/api/v1/sites/erase`, and the account it acts on is named
 * by the `X-Ago-Active-Site` header that `installAgoRestDefaults` attaches to every request — so there is
 * nothing site-shaped for this adapter to read or thread. The bearer token is attached by that same client
 * plugin, never threaded through here.
 *
 * A `202` (or any 2xx) is [EraseAccountResult.Requested]; any other status is read for an RFC 7807 `detail`
 * exactly as [ago.chat.android.core.network.siteexport.KtorSiteExportApi.requestExport] does — a body with a
 * `detail` becomes [EraseAccountResult.Refused], one without becomes
 * [EraseAccountResult.Failed] carrying the status code, never a fabricated string.
 */
public class KtorAccountDeletionApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : AccountDeletionApi {
    override suspend fun eraseAccount(): EraseAccountResult {
        val response =
            try {
                client.post("$apiBaseUrl/api/v1/sites/erase")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return EraseAccountResult.Failed(NetworkFailure.from(failure))
            }

        if (response.status.isSuccess()) {
            // The console's own `eraseSite` reads nothing from the `202` body — only its status matters — so
            // a successful erase is [EraseAccountResult.Requested] with no field to parse and therefore no
            // parse failure to guard against, unlike the export request's own id echo.
            return EraseAccountResult.Requested
        }

        val detail =
            try {
                response.body<ProblemDetailsWireDto>().detail
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                null
            }

        return detail?.let { EraseAccountResult.Refused(it) }
            ?: EraseAccountResult.Failed(NetworkFailure.ServerError(response.status.value))
    }
}

/** RFC 7807, read for exactly the one field a refusal needs — the identical, deliberately un-shared copy
 * every adapter in this codebase keeps for itself. */
@Serializable
private data class ProblemDetailsWireDto(
    val detail: String? = null,
)
