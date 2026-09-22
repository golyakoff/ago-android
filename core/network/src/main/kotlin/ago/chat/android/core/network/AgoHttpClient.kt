package ago.chat.android.core.network

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.network.auth.AccessTokenProvider
import ago.chat.android.core.network.auth.BearerToken
import ago.chat.android.core.network.tenancy.ActiveSiteHeader
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * `26-12`: how this app's authenticated REST client is configured, in one place.
 *
 * Exposed as a `HttpClientConfig` extension rather than only as a finished [HttpClient] so the tests
 * configure a `MockEngine`-backed client **through this same function**. That is what makes the
 * plugin tests statements about the real client rather than about a second, parallel setup that
 * could drift from it — which is what `26-12`'s own Done-when asks for ("asserted by a `MockEngine`
 * test over the client itself, not by reading call sites").
 */
public fun HttpClientConfig<*>.installAgoRestDefaults(
    accessTokens: AccessTokenProvider,
    activeSite: ActiveSiteSelection,
) {
    // A non-2xx is data, not an exception. Every arm of `PostSignInRouter` is a status code, and a
    // client that threw on `403` would turn the platform-owner question into a `catch` block —
    // exactly the "read the absence of one kind as the presence of another" shape `adr/0063` names.
    expectSuccess = false

    install(ContentNegotiation) { json(agoJson) }

    // Order is not significant between these two — one writes a request header in the request
    // pipeline, the other in the send pipeline — but both are installed here, once, so no caller can
    // build an authenticated client that is missing either.
    install(ActiveSiteHeader) { selection = activeSite }
    install(BearerToken) { tokens = accessTokens }

    // Deliberately no `Logging` plugin, at any level, in any build type. It logs request headers,
    // and one of them is a live operator JWT. `ago-console`'s `5-14` is the same lesson learned the
    // expensive way: a transport logging its own negotiated URL at Information level was printing a
    // token on every reconnect.
}

/** The production client: OkHttp engine, per `docs/architecture.md`'s stack table. */
public fun createAgoHttpClient(
    accessTokens: AccessTokenProvider,
    activeSite: ActiveSiteSelection,
): HttpClient =
    HttpClient(OkHttp) {
        installAgoRestDefaults(accessTokens, activeSite)
    }

/**
 * `ignoreUnknownKeys` because the API is versioned and additive (`api-design.md`): a field added
 * server-side must not break a client that predates it. The inverse — a field the client needs and
 * the server stopped sending — is a hard failure, which is what `ago-console`'s own `shapeGuard.ts`
 * exists to produce there and what a missing non-nullable property produces here for free.
 */
internal val agoJson: Json =
    Json {
        ignoreUnknownKeys = true
    }
