package ago.chat.android.devices

import ago.chat.android.core.domain.devices.PushProvider
import com.google.android.gms.common.ConnectionResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `26-100`/`adr/0181`: [selectedProviderFor] on a plain JVM - the pure decision [TransportSelector] itself
 * wraps around a real `GoogleApiAvailability` call, tested here with no `Context` and no Play Services
 * binary on the classpath, the same "shield the SDK call, test the pure decision" split
 * [RuStorePushGatewayTest] already establishes for RuStore's own SDK surface. `ConnectionResult` itself is
 * safe to reference directly in a JVM test - it is a plain holder of `int` constants from
 * `play-services-base`, not a class that reaches into the Android framework the way `GoogleApiAvailability`
 * itself does.
 */
class TransportSelectorTest {
    @Test
    fun `Play Services available and usable selects FCM`() {
        assertEquals(PushProvider.Fcm, selectedProviderFor(ConnectionResult.SUCCESS))
    }

    @Test
    fun `Play Services not installed falls back to RuStore`() {
        assertEquals(PushProvider.RuStore, selectedProviderFor(ConnectionResult.SERVICE_MISSING))
    }

    @Test
    fun `Play Services present but needing an update falls back to RuStore`() {
        assertEquals(PushProvider.RuStore, selectedProviderFor(ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED))
    }

    @Test
    fun `Play Services disabled falls back to RuStore`() {
        assertEquals(PushProvider.RuStore, selectedProviderFor(ConnectionResult.SERVICE_DISABLED))
    }
}
