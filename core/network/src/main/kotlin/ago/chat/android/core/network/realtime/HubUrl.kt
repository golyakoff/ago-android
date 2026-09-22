package ago.chat.android.core.network.realtime

import java.net.URLEncoder

/** `OperatorIdentityClaimsTransformation.ActiveSiteQueryParameterName` server-side — the identical
 * spelling `ago-console`'s own hub connection already uses for the same parameter. */
internal const val ACTIVE_SITE_QUERY_PARAM: String = "activeSite"

/**
 * `docs/architecture.md` §Tenancy: the hub carries the active site as a query-string parameter, never
 * a header — a browser cannot attach a custom header to a WebSocket upgrade, the identical constraint
 * that already puts the bearer token in the query string instead of an `Authorization` header for
 * this same connection. Pulled out of [OperatorHubConnection] as its own pure function so this exact
 * property — present when a site is known, absent (not empty) when it is not — is testable with no
 * `HubConnection` built at all.
 *
 * `URLEncoder.encode` rather than a hand-rolled escape: every real site id is a GUID (hex digits and
 * hyphens only, never a character this would touch), so encoding is defensive here rather than
 * load-bearing — but a value this class did not mint itself is not a value to trust the shape of.
 */
internal fun buildHubUrl(
    hubUrl: String,
    siteId: String?,
): String =
    if (siteId.isNullOrEmpty()) {
        hubUrl
    } else {
        "$hubUrl?$ACTIVE_SITE_QUERY_PARAM=${URLEncoder.encode(siteId, "UTF-8")}"
    }
