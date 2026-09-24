package ago.chat.android.devices

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.rustore.sdk.pushclient.messaging.exception.RuStorePushClientException
import java.io.IOException

/**
 * `26-101`: [Throwable.toTokenFailure] and [RuStorePushClientException.toPushUnavailableReason] on a
 * plain JVM - both are `internal` top-level functions rather than members of [RuStorePushGateway] itself
 * specifically so this class can call them directly with real exception instances, with no `Task`, no
 * `RuStorePushClient` singleton, and no Android runtime anywhere in the test.
 *
 * The property under test is exactly `docs/backlog/26-101-*.md`'s own Done-when, one exception shape at a
 * time: "no host" and an unrecognised (IPC-shaped) failure both classify as [critical] - a genuine,
 * persistent "push cannot work here" - while an [IOException] (the ordinary shape of a network blip while
 * minting a token) never does.
 */
class RuStorePushGatewayTest {
    @Test
    fun `HostAppNotInstalledException classifies as HostAppNotInstalled and critical`() {
        val failure = RuStorePushClientException.HostAppNotInstalledException("no host").toTokenFailure()

        assertEquals(PushUnavailableReason.HostAppNotInstalled, failure.reason)
        assertEquals(true, failure.critical)
    }

    @Test
    fun `UnauthorizedException classifies as Unauthorized and critical`() {
        val failure = RuStorePushClientException.UnauthorizedException("no account").toTokenFailure()

        assertEquals(PushUnavailableReason.Unauthorized, failure.reason)
        assertEquals(true, failure.critical)
    }

    @Test
    fun `HostAppBackgroundWorkPermissionNotGranted classifies as not critical - pushes still arrive, delayed`() {
        val failure =
            RuStorePushClientException.HostAppBackgroundWorkPermissionNotGranted("delayed").toTokenFailure()

        assertEquals(PushUnavailableReason.HostAppBackgroundWorkNotGranted, failure.reason)
        assertEquals(false, failure.critical)
    }

    @Test
    fun `an IOException classifies as Unknown and not critical - a transient blip, retried quietly`() {
        val failure = IOException("timeout").toTokenFailure()

        assertEquals(PushUnavailableReason.Unknown, failure.reason)
        assertEquals(false, failure.critical)
    }

    @Test
    fun `an unrecognised failure classifies as Unknown and critical - the shape an IPC failure takes`() {
        // RuStore raises no dedicated exception type for a dead `Binder`/`RemoteException` talking to the
        // local host app - this app's own stand-in for "some other, unrecognised failure occurred".
        val failure = RuntimeException("binder died").toTokenFailure()

        assertEquals(PushUnavailableReason.Unknown, failure.reason)
        assertEquals(true, failure.critical)
    }
}
