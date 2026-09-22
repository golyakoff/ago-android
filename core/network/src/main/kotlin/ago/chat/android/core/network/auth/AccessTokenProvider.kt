package ago.chat.android.core.network.auth

/**
 * Where the bearer token comes from, asked **once per request**, never captured.
 *
 * This is the whole point of the interface, and it is an interface rather than a `String` parameter
 * for exactly one reason: `5-16`. The access token is five minutes against a long SSO session
 * (`docs/architecture.md`, Identity), so a token read at construction time is a token that is wrong
 * for most of the session. `ago-console` shipped that defect once and both of its clients
 * independently reached the same correction — `calendarApi.ts`'s "the access token is a parameter,
 * never a module-level capture", and `CalendarOperatorConnection`'s `accessTokenFactory` field,
 * which is "a factory, not a token, for the identical `5-16` reason". A `suspend` factory is the
 * shape that *cannot* hold a stale value, which is strictly better than a shape that merely
 * happens not to.
 *
 * Both methods suspend because on Android the answer may require a network round trip: AppAuth's
 * own `performActionWithFreshTokens` refreshes off the refresh token when the access token has
 * expired, and there is nothing useful a non-suspending signature could return while that happens.
 *
 * Implemented in `:app` (`AgoAuthSession`) rather than here, because obtaining a token means
 * launching a Custom Tab and persisting AppAuth's `AuthState` in `EncryptedSharedPreferences` —
 * Android-framework work. This module knows only that a token can be asked for.
 */
public interface AccessTokenProvider {
    /**
     * The token to send **now**. `null` when there is no session at all, in which case the request
     * goes out unauthenticated and the server answers — rather than the client inventing a refusal
     * of its own.
     */
    public suspend fun currentAccessToken(): String?

    /**
     * Forces a renewal and returns the new token, or `null` when renewal is impossible (no refresh
     * token, or the SSO session itself has ended).
     *
     * Called by [BearerToken] on a `401` even when the provider believes the current token is still
     * valid: a server that rejects a token it should have accepted — clock skew, a revoked session,
     * a realm restart — is exactly the case an expiry check cannot see.
     */
    public suspend fun refreshAccessToken(): String?
}
