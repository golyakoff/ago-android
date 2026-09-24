package ago.chat.android.devices

import com.google.android.gms.common.ConnectionResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * `26-100`/`adr/0181`: [playServicesAvailabilityFor] and [Throwable.toFcmTokenFailure] on a plain JVM -
 * both are `internal` top-level functions rather than members of [FcmPushGateway] itself specifically so
 * this class can call them directly, with no `Context`, no `FirebaseMessaging` singleton, and no Android
 * runtime anywhere in the test - the identical shape [RuStorePushGatewayTest] already establishes for
 * RuStore's own gateway.
 */
class FcmPushGatewayTest {
    @Test
    fun `Play Services available maps to Available`() {
        assertEquals(PushAvailability.Available, playServicesAvailabilityFor(ConnectionResult.SUCCESS))
    }

    @Test
    fun `Play Services unavailable maps to Unavailable Unknown - no dedicated reason, see this function's own doc comment`() {
        assertEquals(
            PushAvailability.Unavailable(PushUnavailableReason.Unknown),
            playServicesAvailabilityFor(ConnectionResult.SERVICE_MISSING),
        )
    }

    @Test
    fun `an IOException while minting a token classifies as Unknown and not critical - a transient blip`() {
        val failure = IOException("timeout").toFcmTokenFailure()

        assertEquals(PushUnavailableReason.Unknown, failure.reason)
        assertEquals(false, failure.critical)
    }

    @Test
    fun `any other failure classifies as Unknown and critical - FCM has no structured exception hierarchy`() {
        val failure = RuntimeException("token mint failed").toFcmTokenFailure()

        assertEquals(PushUnavailableReason.Unknown, failure.reason)
        assertEquals(true, failure.critical)
    }
}
