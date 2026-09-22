package ago.chat.android.core.network.realtime

import ago.chat.android.core.network.auth.AccessTokenProvider
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx3.rxSingle

/**
 * The bridge between [AccessTokenProvider] (this app's own `suspend`-shaped port) and
 * `com.microsoft.signalr`'s own `withAccessTokenProvider(Single<String>)` — the hub connection's
 * equivalent of `BearerTokenPlugin`'s "read the token fresh on every request" for a transport whose
 * async shape is RxJava rather than a Ktor send pipeline.
 *
 * **Why `Single.defer`, restated for a library that documents it once and does not repeat it.**
 * `com.microsoft.signalr`'s own reference example wraps its access-token logic in `Single.defer { … }`
 * for the identical `5-16` reason this project's `AccessTokenProvider` doc comment already states: the
 * client calls this factory on every negotiate, the first connect and every automatic-reconnect
 * attempt alike, and a `Single` built once and resubscribed must actually *re-run* its logic each
 * time rather than replay a memoised first answer. `Single.defer` guarantees that regardless of
 * whether the coroutine bridge underneath happens to be cold on its own — the same "make the shape
 * itself incapable of holding a stale value" reasoning [AccessTokenProvider] states for using a
 * `suspend` factory instead of a plain `String` at every other seam in this app.
 *
 * Extracted as its own function, not inlined into [OperatorHubConnection], so a unit test can
 * subscribe to the returned `Single` directly and prove the freshness property with no
 * `HubConnection`, no negotiate and no network at all — the same shape `BearerTokenPluginTest`
 * already proves for the REST client.
 */
internal fun freshAccessTokenSingle(
    accessTokens: AccessTokenProvider,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Single<String> = Single.defer { rxSingle(dispatcher) { accessTokens.currentAccessToken().orEmpty() } }
