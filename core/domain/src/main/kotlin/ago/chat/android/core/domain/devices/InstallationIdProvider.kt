package ago.chat.android.core.domain.devices

/**
 * `26-06`: a stable, per-install identifier - generated once and read forever after, never rotated by
 * anything this app does. **Not the push token.** `docs/architecture/push-notifications.md`'s own
 * "Device registration" section states the one decision this exists for: the server row's identity is
 * `(operator_id, installation_id)`, not `(operator_id, token)`, which is what lets a rotated token
 * update an existing row instead of accumulating a dead one on every refresh.
 *
 * Declared here rather than in `:app`, the identical [ago.chat.android.core.domain.identity.ActiveSiteSelection]
 * placement: the interface itself names no Android type, and every real caller
 * ([ago.chat.android.core.domain.devices.DeviceRegistrationApi]'s own three call sites) reasons about
 * it as a plain fact about "this install", not as a detail of whichever store happens to back it on
 * Android today.
 *
 * Survives sign-out (an install keeps its id across a sign-out/sign-in cycle - only a fresh install
 * gets a new one) - which is exactly why `:app`'s implementation must **not** share
 * `SessionStore`'s file: that file is emptied by `AgoAuthSession.signOut()`, on every sign-out, by
 * design.
 */
public interface InstallationIdProvider {
    /** Generates and persists a new id on first call; every later call returns the same value. */
    public suspend fun installationId(): String
}
