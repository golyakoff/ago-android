package ago.chat.android.session

/**
 * Which deployment this build talks to, and under which realm client.
 *
 * Every value here is public by construction — two DNS names, a realm path, a public OIDC client id
 * (`api-design.md`: "the OIDC client id is public by design") and a custom scheme Keycloak already
 * has registered. There is **no secret**, and there is none to leak, which is half of why `26-11`
 * made `ago-android` a public client with PKCE rather than a confidential one whose secret would
 * have had to ship inside an APK and so would not have been a secret at all.
 *
 * Supplied from `BuildConfig` by `di/AppModule`, not read from it directly, so nothing outside that
 * module depends on a generated class — which is what keeps `SignInViewModel` and `AgoAuthSession`
 * testable without an Android build.
 */
public data class OidcConfig(
    /** The realm's issuer URL. OIDC discovery hangs off it; no endpoint is typed out. */
    val issuer: String,
    /** `ago-android`, the public client `26-11` added to the realm. */
    val clientId: String,
    /** `ago-android://callback` — a custom scheme, matching the realm's own `redirectUris` exactly. */
    val redirectUri: String,
    /** `Ago.Chat.Api`'s origin. */
    val apiBaseUrl: String,
    /** The web console, linked to from the platform-owner terminal screen (`scope-inventory.md` §2). */
    val consoleUrl: String,
)
