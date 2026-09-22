package ago.chat.android.signin

import android.content.Intent

/**
 * What [SignInViewModel] needs a session to be able to do — four operations, no AppAuth types, no
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
 */
public interface SignInSession {
    public suspend fun hasSession(): Boolean

    public suspend fun beginAuthorization(): Intent

    public suspend fun completeAuthorization(data: Intent)

    public suspend fun signOut()
}
