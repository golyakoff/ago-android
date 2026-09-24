package ago.chat.android.devices

import ago.chat.android.core.domain.devices.PushProvider
import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `26-100`/`adr/0181`: the one place this app decides FCM vs RuStore, per device - the client-side half
 * of the ADR's transport-selection rule ("the client asks whether Google Play Services is available and
 * usable"; `docs/adr/0181-*.md` §Decision 1).
 *
 * **Asked once, at DI-graph-construction time, not by every caller.** `di/AppModule.kt`'s
 * `providePushRegistrationGateway` calls [selectedProvider] exactly once, to decide which concrete
 * [PushRegistrationGateway] the app's one `@Singleton` binding resolves to for this process's life - the
 * identical "one graph node, not re-asked per call site" shape [RuStorePushGateway]'s own singleton
 * binding already is. A device whose Play-Services state genuinely changes (installed, updated, removed)
 * picks it up on its next process start, which is when Hilt rebuilds this binding anyway - the ADR's own
 * "re-registers on its next sign-in / rotation / periodic job" language, not a live per-request check.
 *
 * **`GoogleApiAvailability` is not the thing under test - [selectedProviderFor] is.** It is a plain
 * top-level function over the bare `Int` `isGooglePlayServicesAvailable` returns, so a JVM test drives
 * every branch with no `Context`, no real Play Services binary on the classpath, and no Robolectric - the
 * identical "shield the SDK call, test the pure decision" split
 * [RuStorePushGateway.toPushUnavailableReason] already establishes for RuStore's own SDK surface, applied
 * here to Play Services' instead.
 */
@Singleton
public class TransportSelector
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        public fun selectedProvider(): PushProvider =
            selectedProviderFor(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context))
    }

/**
 * [ConnectionResult.SUCCESS] is the one result that means "FCM will actually work here" - every other
 * value (not installed, needs an update, disabled, invalid, or any other documented
 * `ConnectionResult` constant) falls back to RuStore, the identical fallback
 * [FcmPushGateway.checkAvailability] reports through [playServicesAvailabilityFor] for the FCM gateway's
 * own availability question. `internal`, not `private`: [TransportSelectorTest] calls this directly with
 * a bare `Int`, never through a real `GoogleApiAvailability`.
 */
internal fun selectedProviderFor(playServicesAvailabilityResult: Int): PushProvider =
    if (playServicesAvailabilityResult == ConnectionResult.SUCCESS) PushProvider.Fcm else PushProvider.RuStore
