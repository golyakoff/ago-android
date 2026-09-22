package ago.chat.android.core.network.identity

import ago.chat.android.core.domain.identity.IdentityApi
import ago.chat.android.core.domain.identity.ProbeFailure
import ago.chat.android.core.domain.identity.ProbeOutcome
import ago.chat.android.core.domain.identity.Tenancy
import ago.chat.android.core.domain.identity.TenancyListing
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * `26-12`: the adapter behind [IdentityApi] — three calls to `Ago.Chat.Api`, translated out of HTTP
 * and into the vocabulary `PostSignInRouter` reasons in.
 *
 * **The whole status-code-to-meaning mapping lives here, and only here.** That is the point of the
 * port: the routing tree never sees an integer, so its "anything else is neither answer" arm is a
 * single exhaustive `when` rather than a chain of comparisons that a later reader can extend
 * wrongly. Every mapping below says which server-side policy produced the status, because that is
 * what makes it a reading of an authoritative answer rather than a guess (`adr/0063`).
 *
 * The `X-Ago-Active-Site` header and the bearer token are **not** in any signature here: both are
 * attached by client plugins (`installAgoRestDefaults`). `26-12`'s own scope calls that out, and it
 * is why this class can be read for "which endpoints does sign-in touch" without also being the
 * place tenancy and credentials are handled.
 */
public class KtorIdentityApi(
    private val client: HttpClient,
    private val apiBaseUrl: String,
) : IdentityApi {
    /**
     * `GET /api/v1/me/tenancies`, gated by `RequireKeycloakIdentity` (`MeEndpoints`, `13-07`).
     *
     * The server already filters to tenancies this identity may actually sign into
     * (`OperatorSignInEligibility`) and sorts them by name, so nothing here re-derives either.
     */
    override suspend fun listMyTenancies(): TenancyListing {
        val response =
            try {
                client.get("$apiBaseUrl/api/v1/me/tenancies")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return TenancyListing.Unanswered(ProbeFailure.Transport(failure.describe()))
            }

        if (!response.status.isSuccess()) {
            return TenancyListing.Unanswered(ProbeFailure.UnexpectedStatus(response.status.value))
        }

        return try {
            val body = response.body<TenanciesResponseDto>()
            TenancyListing.Known(body.tenancies.map { Tenancy(siteId = it.siteId, siteName = it.siteName) })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A `200` whose body is not the promised shape is not "no tenancies" — that reading is
            // what `ago-console`'s own `shapeGuard.ts` was added to stop, after a dropped array
            // looked exactly like an identity with one shop. It is a call that did not answer.
            TenancyListing.Unanswered(ProbeFailure.Malformed(failure.describe()))
        }
    }

    /**
     * `GET /api/v1/operators/me`, gated by `RequireOperatorIdentity` (`OperatorsEndpoints`, `5-08`).
     *
     * `403` is that policy's own refusal — ASP.NET Core's default for "authenticated, but the claim
     * requirement failed" — and is the *only* status read as "no operator seat". A `401` is reserved
     * for "no valid token at all" and reaches the retry arm: by the time it is seen, [BearerToken]
     * has already forced a renewal and re-sent, so it is a refusal a new token did not fix.
     */
    override suspend fun probeOperatorSeat(): ProbeOutcome = probe("$apiBaseUrl/api/v1/operators/me", refusedStatus = 403)

    /**
     * `GET /api/v1/owner/sites?limit=1`, gated by `RequirePlatformOwner` (`OwnerSitesEndpoints`,
     * `12-01`/`12-02`) — the same probe `ago-console`'s `probeOwnerEligibility` makes, for the same
     * routing decision.
     *
     * `limit=1` because nothing here reads the page; only whether the policy admitted the caller.
     *
     * Unlike the console, a `401` here is **not** read as a refusal. `fetchOwnerSites` folds `401`
     * and `403` together into "not-authorized", which is right for a screen asking "may I render
     * this" and wrong for a router asking "which kind of caller is this": an identity that was
     * authenticated enough to receive a `403` from `operators/me` one request earlier cannot
     * legitimately be unauthenticated now, so a `401` is evidence that something is wrong, not
     * evidence that this is a new registrant.
     */
    override suspend fun probeOwnerEligibility(): ProbeOutcome = probe("$apiBaseUrl/api/v1/owner/sites?limit=1", refusedStatus = 403)

    private suspend fun probe(
        url: String,
        refusedStatus: Int,
    ): ProbeOutcome {
        val response: HttpResponse =
            try {
                client.get(url)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                return ProbeOutcome.Unanswered(ProbeFailure.Transport(failure.describe()))
            }

        return when {
            response.status.isSuccess() -> ProbeOutcome.Accepted
            response.status.value == refusedStatus -> ProbeOutcome.Refused
            else -> ProbeOutcome.Unanswered(ProbeFailure.UnexpectedStatus(response.status.value))
        }
    }
}

/**
 * The exception's type and its own message, and nothing this class reached for itself.
 *
 * Deliberately not `toString()` on an arbitrary cause chain and never the request that produced it:
 * this string is rendered on a retry screen and may end up in a bug report, and a request built by
 * this client carries an `Authorization` header.
 */
private fun Exception.describe(): String = "${this::class.simpleName}: ${message ?: "no detail"}"

/** `Ago.Chat.Contracts.TenanciesResponse`, camelCase per ASP.NET Core's default policy. */
@Serializable
private data class TenanciesResponseDto(
    val tenancies: List<TenancyDto>,
)

/** `Ago.Chat.Contracts.TenancyDto`. Both fields are non-nullable on the wire and required here. */
@Serializable
private data class TenancyDto(
    val siteId: String,
    val siteName: String,
)
