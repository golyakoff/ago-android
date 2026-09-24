package ago.chat.android.signin

import android.content.Intent

/**
 * What [SignInViewModel] needs a session to be able to do — six operations, no AppAuth types, no
 * Keycloak.
 *
 * This interface exists for one concrete reason rather than for symmetry: `AgoAuthSession` cannot be
 * constructed in a JVM unit test (it holds a `Context`, an `AuthorizationService` and an
 * `EncryptedSharedPreferences`), and the view model's own job — turning routing outcomes into
 * screens, and telling a cancelled sign-in apart from a failed one — is exactly the part worth
 * testing without a device. The alternative, Robolectric, would mean a second test runtime in this
 * repository for one class, which is the same trade `26-08` already declined for detekt.
 *
 * `Intent` is still in the signature: it is what the Activity must launch, and hiding it behind an
 * app-level type would be an abstraction whose only content is a rename.
 *
 * **`signOut()` split into [beginSignOut]/[completeSignOut], `26-93`.** A single suspend `signOut()`
 * was enough while it only touched local state, but RP-Initiated Logout needs a Custom Tab launch —
 * a real Activity, a real result callback — the identical reason [beginAuthorization]/
 * [completeAuthorization] are already two functions instead of one. [beginSignOut] returns the
 * `Intent` to launch (or `null` when there is nothing at the identity provider worth ending, in which
 * case the caller skips the browser and calls [completeSignOut] with `null` directly);
 * [completeSignOut] is the only one of the two that actually forgets this device's session, and it
 * does so unconditionally — see [ago.chat.android.session.AgoAuthSession.completeSignOut]'s own doc
 * comment for why *how* the round trip ended never changes that.
 */
public interface SignInSession {
    public suspend fun hasSession(): Boolean

    public suspend fun beginAuthorization(): Intent

    public suspend fun completeAuthorization(data: Intent)

    public suspend fun beginSignOut(): Intent?

    public suspend fun completeSignOut(data: Intent?)
}
