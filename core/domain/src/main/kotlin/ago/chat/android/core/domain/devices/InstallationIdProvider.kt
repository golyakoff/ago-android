package ago.chat.android.core.domain.devices

/**
 * `26-06`: a stable, per-install identifier - generated once and read forever after, never rotated by
 * anything this app does. **Not the push token.** `docs/architecture/push-notifications.md`'s own
 * "Device registration" section states the original decision this exists for: a rotated token updates
 * an existing row instead of accumulating a dead one on every refresh.
 *
 * **`26-122`: this value is no longer the server row's identity.** A reinstall regenerates it (this
 * type's own doc comment already said so: "per-install", not "per-device"), which is exactly why
 * `26-83` found rows accumulating across reinstalls. The row's real identity moved to
 * [DeviceIdProvider]'s own value; this one still travels on every registration (kept current on the row
 * so a later sign-out `DELETE` from whichever install currently holds it still finds it) and still
 * addresses the row on the wire, but it no longer decides which row a registration lands on.
 *
 * Declared here rather than in `:app`, the identical [ago.chat.android.core.domain.identity.ActiveSiteSelection]
 * placement: the interface itself names no Android type, and every real caller
 * ([ago.chat.android.core.domain.devices.DeviceRegistrationApi]'s own three call sites) reasons about
 * it as a plain fact about "this install", not as a detail of whichever store happens to back it on
 * Android today.
 *
 * Survives sign-out (an install keeps its id across a sign-out/sign-in cycle - only a fresh install
 * gets a new one) - which is exactly why `:app`'s implementation must **not** share
 * `SessionStore`'s file: that file is emptied by `AgoAuthSession.completeSignOut()`, on every
 * sign-out, by design (`26-93` split what used to be one `signOut()` function into
 * `beginSignOut()`/`completeSignOut()`, but the file is still emptied unconditionally, exactly once,
 * by the second half).
 */
public interface InstallationIdProvider {
    /** Generates and persists a new id on first call; every later call returns the same value. */
    public suspend fun installationId(): String
}
