package ago.chat.android.session

/**
 * `26-77`: the two facts the account menu's own header shows — a display name and an email/username —
 * read from the signed-in operator's own ID token, never fetched from a server endpoint of this item's
 * own invention.
 *
 * **Why the ID token, and not a new `Ago.Chat.Api` call.** `AppModule`'s own doc comment on
 * `provideOperatorPermissionsApi` already states this project's rule against widening a narrow port to
 * answer a second, unrelated question — the identical reasoning applies here, one level earlier: this
 * app already asks Keycloak for `openid profile email` (`AgoAuthSession.SCOPE`) at every sign-in, and
 * `AuthState.parsedIdToken` is AppAuth's own cache of exactly the claims that scope buys, sitting in
 * memory from the moment sign-in completes. Reading it costs nothing this app has not already paid for;
 * a new endpoint would duplicate a call already made and add a fourth `Ago.Chat.Api` round trip to a
 * screen whose whole point is to render instantly.
 *
 * Both fields are nullable because Keycloak's own realm mapper configuration decides what the token
 * actually carries — a claim this client asked for is not one the identity provider is obliged to
 * answer, and this type says so rather than inventing a value it cannot promise.
 */
public data class OperatorIdentity(
    val displayName: String?,
    val email: String?,
)

/**
 * The narrow port [ago.chat.android.shell.AppShellViewModel] reads through — [AgoAuthSession]'s own
 * `SignInSession`/`AccessTokenProvider` split, restated for identity. A separate interface rather than
 * a third method on `SignInSession`: that interface's own doc comment scopes itself to "what
 * `SignInViewModel` needs", and the account menu's own reader is a different caller asking a different
 * question, not one more reason to widen a file that already states its own boundary.
 */
public interface OperatorIdentityProvider {
    /** `null` only before any sign-in has ever completed in this process — once [AgoAuthSession] holds
     * a real [net.openid.appauth.AuthState], this returns whatever the ID token itself carried, however
     * incomplete. */
    public suspend fun currentIdentity(): OperatorIdentity?
}
