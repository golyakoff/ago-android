package ago.chat.android.core.domain.identity

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * One `Site` this identity may sign into — `adr/0068`'s "one identity, several tenancies".
 *
 * The server does not hand back every `operators` row: `ListMyTenanciesHandler` (`ago-chat`) filters
 * by `OperatorSignInEligibility.CanSignInAsync` and sorts by name, so a tenancy that appears here is
 * one the token can actually resolve to. That is load-bearing for [PostSignInRouter]: a non-empty
 * list is itself authoritative evidence that an operator seat exists.
 */
public data class Tenancy(
    val siteId: String,
    val siteName: String,
)

/** The answer to `GET /api/v1/me/tenancies`, with the same three-valued discipline as [ProbeOutcome]. */
public sealed interface TenancyListing {
    public data class Known(
        val tenancies: List<Tenancy>,
    ) : TenancyListing

    public data class Unanswered(
        val reason: NetworkFailure,
    ) : TenancyListing
}

/**
 * Which tenancy every authenticated request is currently acting in — the app's single source of
 * truth for `X-Ago-Active-Site`, and the exact role `ago-console/src/api/activeSite.ts` plays there.
 *
 * Declared here, in `:core:domain`, rather than beside the Ktor plugin that reads it, because "which
 * shop am I working in" is a product fact, not a detail of one transport: `26-13`'s hub carries the
 * same value as a query-string parameter rather than a header, off this same source. The alternative
 * — putting it in `:core:network` — would make `:core:domain`'s own router unable to sequence the
 * selection before the probe that depends on it, which is the one ordering this whole flow turns on.
 *
 * Implemented in `:app`, because persisting it is an Android concern (`SessionStore`).
 */
public interface ActiveSiteSelection {
    /** Read fresh at every call site. Never captured — the same rule the bearer token is under. */
    public fun currentSiteId(): String?

    public fun select(siteId: String?)
}
