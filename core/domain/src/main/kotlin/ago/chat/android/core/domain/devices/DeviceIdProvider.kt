package ago.chat.android.core.domain.devices

/**
 * `26-122`: a stable identifier for the *physical device* (survives a reinstall), as opposed to
 * [InstallationIdProvider]'s own value (stable only for the life of one app install). `26-83` found one
 * operator holding five live `operator_devices` rows - one FCM plus four stale RuStore - from repeated
 * reinstalls/re-logins, because [InstallationIdProvider]'s own value is generated fresh on every
 * install and therefore cannot dedup across one (that interface's own doc comment already states this
 * plainly: "a stable, per-install identifier" - an install is not a device).
 *
 * **Declared here rather than in `:app`, the identical [InstallationIdProvider] placement**: the
 * interface itself names no Android type, and its one real caller
 * ([ago.chat.android.devices.DeviceRegistrationCoordinator]) reasons about it as a plain fact about
 * "this device", not as a detail of whichever framework API happens to answer it on Android today
 * (`adr/0178`: `:core:domain` stays a plain Kotlin module a KMP `commonMain` could absorb unchanged).
 *
 * **Trade-off, stated once here rather than only in a backlog item.** Two real options exist:
 * - `Settings.Secure.ANDROID_ID` - stable per (signing key, app, user, device) since Android 8, requires
 *   no permission, and **survives a reinstall of this exact app**, which is the one property this port
 *   exists for. Its caveats are real and accepted: it changes on a factory reset (device identity
 *   genuinely changed, which `26-123`'s time-based prune exists to absorb for exactly this and similar
 *   cases), it differs between a debug and a release build (different signing keys - each is its own
 *   "device" for this app's purposes, which is harmless: nobody reinstalls a debug build over a release
 *   one in practice), and a small number of devices/emulators report `null` or the long-documented
 *   all-devices sentinel `9774d56d682e549c`.
 * - A second stored UUID, the identical shape [InstallationIdProvider] already is - simpler, but a
 *   plain reinstall wipes the app's private storage (`DataStoreInstallationId`'s own doc comment already
 *   states this for the first one), so a second copy of the same mechanism would degrade to the
 *   identical same-install-only limit this port exists to fix, unless it also opted into Android's
 *   backup/restore path - a real device just to test, and a mechanism this codebase does not otherwise
 *   use anywhere.
 *
 * **Chosen: `ANDROID_ID`, with the stored id as its own fallback for the invalid-value case** - the
 * implementation ([ago.chat.android.devices.AndroidIdDeviceIdProvider]) reads `ANDROID_ID` first and
 * falls back to [InstallationIdProvider]'s own value only when the platform value is missing or the
 * known-bad sentinel, which keeps `26-123`'s safety net as the backstop for that narrow case rather
 * than as this feature's primary mechanism.
 */
public interface DeviceIdProvider {
    /** A value stable for the life of this physical device (this app's signing key, this user profile) -
     * unlike [InstallationIdProvider.installationId], survives a reinstall. */
    public suspend fun deviceId(): String
}
