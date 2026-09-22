package ago.chat.android.core.network.tenancy

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin

/**
 * The exact header name `OperatorIdentityClaimsTransformation` (`ago-chat`) reads, and the same
 * constant `ago-console/src/api/activeSite.ts` exports for the same purpose. Spelled once.
 */
public const val ACTIVE_SITE_HEADER_NAME: String = "X-Ago-Active-Site"

/** Configuration for [ActiveSiteHeader]. */
public class ActiveSiteHeaderConfig {
    public var selection: ActiveSiteSelection? = null
}

/**
 * Adds `X-Ago-Active-Site` to every request this client makes, when one is known.
 *
 * **A client plugin, not a parameter.** `docs/architecture.md` §Tenancy asks for exactly this — "a
 * Ktor client plugin in `:core:network` that adds the header to every request, reading a single
 * source of truth… not threaded through call signatures, and not duplicated per API module". The
 * console reached the same conclusion from the other direction and wrote down why
 * (`activeSite.ts`): threading a site id through every call signature would touch every exported
 * function in every API module and every one of their call sites, for something that is a UX
 * convenience rather than a security boundary. A native client inherits that reasoning unchanged,
 * and gets the stronger version of it for free: a Ktor plugin cannot be *forgotten* at a call site
 * the way a parameter can.
 *
 * **Why a stale read is safe.** `adr/0068`'s own "Negative consequences" paragraph:
 * `ResolveOperatorIdentityHandler` resolves `(sub, requested site)` and refuses outright on a
 * mismatch — it never falls back to a different tenancy this identity holds. So this value can only
 * ever *narrow* what a request resolves to, never widen it. A request that goes out naming the
 * previous tenancy for one tick during a switch costs one `403` and a retry, never a cross-tenant
 * leak. That is the property that makes a plugin reading mutable state an acceptable design here
 * rather than a race worth engineering away.
 *
 * **Read fresh per request**, for the same reason the bearer token is: `26-17`'s switcher will write
 * this while the app is running, and a plugin that captured the value at install time would keep
 * naming the old tenancy until the process restarted.
 */
public val ActiveSiteHeader: ClientPlugin<ActiveSiteHeaderConfig> =
    createClientPlugin("AgoActiveSite", ::ActiveSiteHeaderConfig) {
        val selection =
            requireNotNull(pluginConfig.selection) {
                "ActiveSiteHeader requires an ActiveSiteSelection; install it through installAgoRestDefaults()."
            }

        onRequest { request, _ ->
            val siteId = selection.currentSiteId()
            if (siteId.isNullOrEmpty()) {
                // Absent, not empty. `ResolveOperatorIdentityHandler` treats an absent header as
                // "no site requested" and resolves the identity's single tenancy; an empty string
                // would be a requested site that matches no row, which it refuses.
                request.headers.remove(ACTIVE_SITE_HEADER_NAME)
            } else {
                request.headers[ACTIVE_SITE_HEADER_NAME] = siteId
            }
        }
    }
