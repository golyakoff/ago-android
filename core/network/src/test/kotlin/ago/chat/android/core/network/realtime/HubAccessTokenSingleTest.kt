package ago.chat.android.core.network.realtime

import ago.chat.android.core.network.MutableAccessTokenProvider
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `26-13`'s own port of the `5-16` regression `BearerTokenPluginTest` already proves for the REST
 * client: a client that captured its token once - at construction, or the first time a `Single` was
 * subscribed to - fails this, because AppAuth renews the access token on its own schedule
 * (`docs/architecture.md`, Identity) and every negotiate after that renewal must present the new one.
 *
 * `blockingGet()` is RxJava's own synchronous test-consumption method, not production code - the
 * production caller is `OperatorHubConnection`'s `await()`-based suspend path. Reused
 * [MutableAccessTokenProvider] from `core.network`'s own `TestDoubles.kt` (an `internal` class, so
 * visible anywhere in this module) rather than a second copy of the identical fake.
 */
class HubAccessTokenSingleTest {
    @Test
    fun `each subscription reads the token fresh, never a value captured once`() {
        val tokens = MutableAccessTokenProvider(token = "first-token")
        val single = freshAccessTokenSingle(tokens, dispatcher = Dispatchers.Unconfined)

        assertEquals("first-token", single.subscribeOn(Schedulers.trampoline()).blockingGet())
        assertEquals("one read for the first subscription", 1, tokens.currentReads)

        tokens.token = "rotated-by-appauth-in-the-background"

        assertEquals("rotated-by-appauth-in-the-background", single.subscribeOn(Schedulers.trampoline()).blockingGet())
        assertEquals("a second, independent read for the second subscription", 2, tokens.currentReads)
    }

    @Test
    fun `no session at all reads back as an empty string, never a null the RxJava contract forbids`() {
        val tokens = MutableAccessTokenProvider(token = null)
        val single = freshAccessTokenSingle(tokens, dispatcher = Dispatchers.Unconfined)

        val value = single.subscribeOn(Schedulers.trampoline()).blockingGet()

        assertEquals("", value)
        assertNull("the underlying provider genuinely has no token", tokens.token)
    }
}
