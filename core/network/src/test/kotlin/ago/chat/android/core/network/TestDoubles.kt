package ago.chat.android.core.network

import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.network.auth.AccessTokenProvider

/**
 * An access token that can be changed between calls — which is the only way to tell a client that
 * *reads* its token apart from one that *captured* it. `5-16`'s regression test needs exactly this
 * and nothing more.
 */
internal class MutableAccessTokenProvider(
    var token: String?,
    private val renewal: (() -> String?)? = null,
) : AccessTokenProvider {
    var currentReads: Int = 0
        private set
    var refreshes: Int = 0
        private set

    override suspend fun currentAccessToken(): String? {
        currentReads++
        return token
    }

    override suspend fun refreshAccessToken(): String? {
        refreshes++
        val renewed = renewal?.invoke()
        token = renewed
        return renewed
    }
}

/** An in-memory [ActiveSiteSelection], the same contract `:app`'s `SessionStore`-backed one has. */
internal class InMemoryActiveSite(
    private var siteId: String? = null,
) : ActiveSiteSelection {
    override fun currentSiteId(): String? = siteId

    override fun select(siteId: String?) {
        this.siteId = siteId
    }
}
