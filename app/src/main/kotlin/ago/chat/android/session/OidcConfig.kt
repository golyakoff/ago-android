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
    /**
     * `26-93`: `ago-android://logout-callback` — the same custom scheme as [redirectUri], a
     * different path. No second manifest entry is needed for it: `RedirectUriReceiverActivity`'s own
     * `<intent-filter>` (`net.openid.appauth`'s library manifest, merged via the
     * `appAuthRedirectScheme` placeholder `app/build.gradle.kts` sets) declares `android:scheme` alone,
     * with no `android:host`/`android:path`, so it already catches every URI under this scheme —
     * confirmed by reading that filter out of the resolved `appauth-0.11.1.aar` rather than assumed.
     * It still has to be a *different* URI than [redirectUri], because Keycloak validates
     * `post_logout_redirect_uri` against the realm client's own registered list independently of
     * `redirectUris`, and reusing the sign-in one would make `MainActivity` unable to tell "a sign-in
     * just completed" from "a sign-out round trip just completed" apart.
     */
    val postLogoutRedirectUri: String,
    /** `Ago.Chat.Api`'s origin. */
    val apiBaseUrl: String,
    /** The web console, linked to from the platform-owner terminal screen (`scope-inventory.md` §2). */
    val consoleUrl: String,
    /**
     * `26-48`: `Ago.Calendar.Api`'s own origin — a *different* backend than [apiBaseUrl], on its own
     * deployment. `null` when this deployment does not run AGO Calendar at all, the identical
     * `string | null` shape `ago-console`'s own `config.calendarApiBaseUrl` already carries
     * (`calendarApi.ts`'s own doc comment) — never a guessed or default hostname.
     */
    val calendarApiBaseUrl: String?,
)
