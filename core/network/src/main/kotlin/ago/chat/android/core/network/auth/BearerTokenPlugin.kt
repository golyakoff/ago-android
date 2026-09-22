package ago.chat.android.core.network.auth

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

/** Configuration for [BearerToken]. */
public class BearerTokenConfig {
    public var tokens: AccessTokenProvider? = null
}

/**
 * Attaches `Authorization: Bearer …` to every request, **reading the token fresh each time**, and
 * retries a `401` exactly once behind a forced renewal.
 *
 * ## Why this is hand-written rather than Ktor's own `Auth`/`bearer` plugin
 *
 * `BearerAuthProvider` caches the token it loaded and only re-reads it through `refreshTokens` after
 * a `401`. That cache is the `5-16` shape: AppAuth renews on its own schedule (a background refresh
 * a minute before expiry, the same way `oidc-client-ts`'s `automaticSilentRenew` does in the
 * console), and a client holding a cached copy would keep sending the previous one until a server
 * refused it. Here the token is asked for inside the send pipeline, per attempt, so a renewal that
 * happened anywhere else in the app is already in effect on the next request — no invalidation, no
 * shared mutable copy, nothing to keep in step.
 *
 * ## Why the header is set in the `Send` hook, not in `onRequest`
 *
 * `onRequest` runs once, in the request pipeline, before the send pipeline this hook lives in. A
 * retry re-enters `proceed` with the *same* builder, so a header written by `onRequest` would be the
 * pre-refresh token on the second attempt — a retry that re-sends the credential the server just
 * rejected. Writing it here means the retried request carries the renewed token, which is the only
 * version of this behaviour worth having. `BearerTokenPluginTest` asserts that specific byte.
 *
 * ## What it deliberately does not do
 *
 * It does not log. Nothing in this module installs Ktor's `Logging` plugin, and no line here ever
 * renders a token: `docs/architecture.md`'s "the refresh token never leaves the device and never
 * appears in a log" is a property of what is *absent* from this file, so it is stated here rather
 * than left to be noticed. `ago-console`'s `5-14` is the precedent — a transport that logged its own
 * negotiated URL at Information level was logging a live operator JWT.
 *
 * It also retries only once. A second `401` after a successful renewal is a real refusal, and the
 * caller's own `ProbeOutcome.Unanswered` arm is where it belongs — not in a loop.
 */
public val BearerToken: ClientPlugin<BearerTokenConfig> =
    createClientPlugin("AgoBearerToken", ::BearerTokenConfig) {
        val tokens =
            requireNotNull(pluginConfig.tokens) {
                "BearerToken requires an AccessTokenProvider; install it through installAgoRestDefaults()."
            }

        on(Send) { request ->
            request.setBearer(tokens.currentAccessToken())
            val call = proceed(request)
            if (call.response.status != HttpStatusCode.Unauthorized) {
                return@on call
            }

            val renewed = tokens.refreshAccessToken() ?: return@on call
            request.setBearer(renewed)
            proceed(request)
        }
    }

private fun HttpRequestBuilder.setBearer(token: String?) {
    if (token.isNullOrEmpty()) {
        headers.remove(HttpHeaders.Authorization)
    } else {
        headers[HttpHeaders.Authorization] = "Bearer $token"
    }
}
