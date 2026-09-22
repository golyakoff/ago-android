plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // `26-12`: Hilt runs through KSP rather than kapt — Dagger has supported it since 2.48 and it
    // is the faster of the two. The Hilt Gradle plugin is what rewrites the `Application` class's
    // bytecode so `@HiltAndroidApp` does not need a generated base class typed out by hand.
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
}

/**
 * `26-09`'s own `-PagoVersionName` shape, generalised: a Gradle property when one is passed,
 * otherwise the deployment this repository actually targets. Returns the value **already quoted**,
 * because `buildConfigField("String", ...)` takes a Java source literal rather than a value.
 */
fun agoProperty(
    name: String,
    default: String,
): String {
    val value = (project.findProperty(name) as String?) ?: default
    return "\"" + value + "\""
}

android {
    namespace = "ago.chat.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "ago.chat.android"
        // 26 (Android 8.0) is the floor rather than an arbitrary low number: notification
        // channels — the shape `plan.md`'s Notification-settings screen is designed around,
        // "a channel per kind of event" — do not exist before API 26, and there is no reason
        // to support a phone that cannot render the feature the whole app exists for.
        minSdk = 26
        targetSdk = 34

        // `26-09`/`adr/0051`: the build is a function of the commit alone, so the commit is the
        // only truthful name for it — the same rule that keeps a GHCR image tag honest in the
        // backend and frontend repositories, ported here. CI passes `-PagoVersionName` (the short
        // commit sha) and `-PagoVersionCode` (`github.run_number`, monotonic across the repo's
        // whole history — a commit sha cannot serve as `versionCode` itself, since Android
        // requires it to be an increasing integer). Left unset, a local `./gradlew assembleDebug`
        // still works and says so rather than claiming a commit it was not built from — the same
        // choice `GIT_COMMIT` defaults to `unknown` for in the three frontend Dockerfiles.
        versionCode = (project.findProperty("agoVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("agoVersionName") as String?) ?: "0.1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // `26-12`: AppAuth's own `RedirectUriReceiverActivity` is declared in the library's manifest
        // with a placeholder-valued `android:scheme`, so the scheme is supplied here
        // rather than by this app declaring a second receiver activity of its own. It must match the
        // redirect URI `26-11` registered on the `ago-android` Keycloak client exactly.
        manifestPlaceholders["appAuthRedirectScheme"] = "ago-android"

        // `26-12`: the deployment this build talks to. Public DNS names, not secrets — the same two
        // hostnames `ago-console` ships in its own runtime config, and the OIDC client id is public
        // by design (`api-design.md`). They are `BuildConfig` fields rather than Kotlin constants so
        // a future build variant (`26-17`'s "О приложении" reads the variant's own name) can point a
        // build at a different deployment without a source change, and overridable by a Gradle
        // property the same way `26-09` already overrides the version fields.
        buildConfigField("String", "AGO_API_BASE_URL", agoProperty("agoApiBaseUrl", "https://chat-api.reserve-me.ru"))
        buildConfigField("String", "AGO_KEYCLOAK_ISSUER", agoProperty("agoKeycloakIssuer", "https://auth.reserve-me.ru/realms/ago-chat"))
        buildConfigField("String", "AGO_OIDC_CLIENT_ID", agoProperty("agoOidcClientId", "ago-android"))
        buildConfigField("String", "AGO_OIDC_REDIRECT_URI", agoProperty("agoOidcRedirectUri", "ago-android://callback"))
        buildConfigField("String", "AGO_CONSOLE_URL", agoProperty("agoConsoleUrl", "https://office.reserve-me.ru"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Off by default since AGP 8; the five fields above need it.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // The only module allowed to see :core:network directly — :app wires the two library
    // modules together and is the only place DI is wired (`26-12`, `docs/architecture.md`).
    implementation(project(":core:network"))
    implementation(project(":core:domain"))

    // `26-12`: dependency injection, identity, and the encrypted token store. Each replaces
    // infrastructure this project has no reason to own: Hilt replaces a hand-rolled service
    // locator; AppAuth replaces hand-rolled Authorization Code + PKCE (the exact security-sensitive
    // plumbing `ago-console` already reaches for `oidc-client-ts` over); `security-crypto` replaces
    // hand-rolled Android Keystore envelope encryption over plain SharedPreferences.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // `26-14`: `hiltViewModel()` for `ConversationListRoute` - see this catalog entry's own remarks
    // for why it is needed before Navigation Compose itself is (`26-16`).
    implementation(libs.hilt.navigation.compose)
    // `26-16`: the real navigation graph - the bottom bar's five destinations and the back-button
    // contract (`docs/backlog/26-16-*.md`).
    implementation(libs.androidx.navigation.compose)
    implementation(libs.appauth)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    // `26-14`: Room, the conversation list's stale-until-proven-fresh cache
    // (`docs/architecture.md` "Offline"). Lives here, not in a `:core:*` module, for the identical
    // "a concrete Android technology is wired in :app, behind a :core:domain port" reason
    // `SessionStore`/`AgoActiveSite` already establish for `EncryptedSharedPreferences`.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // `26-13`: `ProcessLifecycleOwner`, the whole-app foreground signal
    // `OperatorHubConnectionLifecycle` observes - see this catalog entry's own remarks for why an
    // `Activity`'s own lifecycle is the wrong signal for this.
    implementation(libs.androidx.lifecycle.process)
    // `26-14`: `LocalLifecycleOwner`/`collectAsStateWithLifecycle` - see this catalog entry's own
    // remarks for why the older copy in `androidx.compose.ui:ui` is not used instead.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    // `26-14`: `Room.inMemoryDatabaseBuilder` for `RoomConversationListCacheTest` - real SQLite, which
    // only an instrumented test can provide without Robolectric (this catalog entry's own remarks).
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
