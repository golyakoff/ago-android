import java.util.Properties

plugins {
    // `25-214`: no `org.jetbrains.kotlin.android` here. AGP 9.0 compiles Kotlin itself ("built-in
    // Kotlin"), and applying the standalone plugin on top of it is a hard build failure, not a
    // warning: "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support
    // since AGP 9.0". The `kotlin { }` block below is now AGP's own extension rather than the
    // Kotlin Gradle plugin's, which is why it keeps working unchanged.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // `26-12`: Hilt runs through KSP rather than kapt — Dagger has supported it since 2.48 and it
    // is the faster of the two. The Hilt Gradle plugin is what rewrites the `Application` class's
    // bytecode so `@HiltAndroidApp` does not need a generated base class typed out by hand.
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
}

// `26-24`/`26-38`: `MAJOR` alone is the one version component a person still sets rather than a
// build deriving — deliberately not computed from anything, because "is this still a 0.x product or
// has it become a 1.0 one" is the author's own call, in the semver sense of "I am promising something
// different now", not a fact about commit history. Bumped by hand, in its own commit, only when the
// author actually decides that.
//
// A plain `val`, not `const val`: a Gradle Kotlin DSL script's own top level is the body of the
// generated script class, and `const` is only legal on a real top level or in an object — so
// `const val` here is a script-compilation failure, not a style choice. `ci.yml`'s "Compute
// version inputs" step reads this line's literal out of this file, so the declaration is kept on
// one line with the value in double quotes.
val agoMajorVersion = "0"

// `26-38`: `MINOR` and `PATCH` are both facts about the repository, not decisions — semver's own
// definition of the two ("MINOR: added functionality in a backward-compatible manner", "PATCH:
// backward-compatible bug fixes") is answerable from the commits themselves, via the `feat`/`fix`
// prefix this project's own commit convention already carries on every change. `ci.yml`'s "Compute
// version inputs" step finds the highest existing `v<major>.*.*` tag, looks at every commit since it
// (the identical range the changelog step below builds from), and bumps MINOR - resetting PATCH to
// zero - the moment any of them is a real `feat`, otherwise bumps PATCH alone. A release that mixes
// features and fixes together is a minor release, the standard reading: a fix riding along with a
// feature is still part of "added functionality", not a fix-only release.
//
// The full `MAJOR.MINOR.PATCH` arrives as `-PagoReleaseVersion`, so the APK's own filename and
// `versionName` below name the exact version the release job is about to tag and publish - the two
// must never disagree, since `ci.yml`'s own "Check the APK can name its own commit" step and the
// tag-vs-filename convention both assume they don't. Left unset, a local, unversioned build still
// works and still says so honestly: `<major>.0.0+dev` cannot be mistaken for a real, CI-computed
// version - MINOR/PATCH have no meaningful "current" value outside of CI's own tag-history read, so
// zero is a placeholder here, not a guess at what the next real release will be.
val agoReleaseVersion = (project.findProperty("agoReleaseVersion") as String?) ?: "$agoMajorVersion.0.0"

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

// `25-215`: the four `agoSigning*` values have two possible sources, never both read the same way.
// A developer machine keeps them in `local.properties` — gitignored, per-machine, the same file
// Android Studio itself writes into — because a store/key password has no business as a shell
// history entry. CI has no such file (a runner is thrown away after the job), so it passes them as
// `-P` Gradle properties sourced from `secrets.*` instead. `Properties().load(...)` against
// `rootProject.file("local.properties")` is Gradle's own idiom for the first source; a fresh
// checkout with neither source present must still build (AGP's own default debug signing,
// `release` left genuinely unsigned), so this returns null rather than a default — unlike
// `agoProperty` above, whose fields (API URLs, version name) always have a safe literal to fall
// back to. A signing password does not have one.
val agoLocalProperties = Properties()
val agoLocalPropertiesFile = rootProject.file("local.properties")
if (agoLocalPropertiesFile.exists()) {
    agoLocalPropertiesFile.inputStream().use { agoLocalProperties.load(it) }
}

fun agoSigningProperty(name: String): String? = agoLocalProperties.getProperty(name) ?: (project.findProperty(name) as String?)

// Read once, at configuration time, and used as the guard for the whole `signingConfigs`/
// `buildTypes` wiring below: its presence is what distinguishes an environment that has the shared
// keystore (local dev with `local.properties` populated, or CI with its two repository secrets)
// from one that does not (a fresh clone, or a contributor's machine with no keystore yet), so the
// unconfigured case gets today's behaviour unchanged rather than a build failure.
val agoSigningKeystorePath = agoSigningProperty("agoSigningKeystorePath")

android {
    namespace = "ago.chat.android"

    // `25-214`: 37 is not a free choice — it is the floor `androidx.core:core-ktx` 1.19.0 and the
    // 2026.09.00 Compose BOM compile against, and simultaneously the ceiling AGP 9.4 supports, so
    // it moves in lockstep with `agp`/the Gradle wrapper rather than on its own (see
    // `gradle/libs.versions.toml`'s own `agp` remarks). `targetSdk` deliberately does NOT follow:
    // `compileSdk` only says which APIs this code may *name*, while `targetSdk` opts the running
    // app into a platform generation's behaviour changes, which is a product decision with real
    // runtime consequences and no bearing on the dependency wall this item exists to clear.
    compileSdk = 37

    defaultConfig {
        applicationId = "ago.chat.android"
        // 26 (Android 8.0) is the floor rather than an arbitrary low number: notification
        // channels — the shape `plan.md`'s Notification-settings screen is designed around,
        // "a channel per kind of event" — do not exist before API 26, and there is no reason
        // to support a phone that cannot render the feature the whole app exists for.
        minSdk = 26
        targetSdk = 34

        // `26-09`/`adr/0051` made every field here a function of the commit alone, on the grounds
        // that nothing else about a build is guaranteed reproducible. `26-24` split that rule in
        // two rather than dropping it, because a *product* version is a decision nobody can derive
        // from a commit:
        //
        //   - `versionCode` stays exactly what it was — `github.run_number`, monotonic across the
        //     repository's whole history, which is what Android requires this field to be (a commit
        //     sha cannot serve as it, being neither an integer nor increasing). It remains the
        //     "which CI run built this" provenance it has always been.
        //   - `versionName` is now `agoReleaseVersion` above (`26-38`: hand-set base, auto-computed
        //     patch), with the commit carried *alongside* it as semver build metadata
        //     (`MAJOR.MINOR.PATCH+<short sha>`, the `+` being the spec's own build-metadata
        //     separator) rather than standing in for it. So the commit-provenance guarantee is
        //     unchanged in substance — the APK still names the exact commit it was built from, and
        //     CI still reads it back out with `aapt2 dump badging` — it just no longer monopolises
        //     the field.
        //
        // Left unset, a local `./gradlew assembleDebug` still works and still says so honestly:
        // `0.1.0+dev` occupies the same build-metadata slot a real build puts its sha in, so it
        // cannot be mistaken for, or matched as, a commit it was not built from — the same choice
        // `GIT_COMMIT` defaults to `unknown` for in the three frontend Dockerfiles.
        versionCode = (project.findProperty("agoVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = agoReleaseVersion + "+" + ((project.findProperty("agoVersionName") as String?) ?: "dev")

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

    // `25-215`: one persistent keystore signs both build types, rather than `debug`'s per-run AGP
    // default and a separately-keyed `release`. `signingConfigs` is only populated when
    // `agoSigningKeystorePath` is actually present — the same guard is repeated on each build type
    // below rather than assigning unconditionally, because `signingConfig = signingConfigs.getByName(...)`
    // would fail to resolve on a fresh checkout where the block was never created. Left unconfigured,
    // `debug` keeps AGP's own built-in debug-signing default (unchanged from today) and `release`
    // stays genuinely unsigned (`isMinifyEnabled = false`, unchanged by `25-214`) rather than
    // failing the whole build.
    signingConfigs {
        if (agoSigningKeystorePath != null) {
            create("ago") {
                storeFile = file(agoSigningKeystorePath)
                storePassword = agoSigningProperty("agoSigningStorePassword")
                // Not secret — the alias may be a plain literal in the build file or workflow.
                keyAlias = agoSigningProperty("agoSigningKeyAlias") ?: "ago-android"
                keyPassword = agoSigningProperty("agoSigningKeyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (agoSigningKeystorePath != null) {
                signingConfig = signingConfigs.getByName("ago")
            }
        }
        release {
            isMinifyEnabled = false
            if (agoSigningKeystorePath != null) {
                signingConfig = signingConfigs.getByName("ago")
            }
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

// `26-24`: the published release APK is named for the product, not for AGP's own default
// `app-release.apk` — a file a tester downloads from a GitHub release page onto a phone should say
// what it is. Bare semver, no `+<sha>` build metadata: the filename is what a person reads, while
// the manifest's `versionName` inside is what proves which commit it came from (and `+` is not a
// character worth putting in a filename that travels through browsers and file managers).
//
// This is the AGP 9 variant API (`androidComponents.onVariants`), not the `applicationVariants.all
// { outputs.forEach { (it as BaseVariantOutputImpl).outputFileName = ... } }` shape most search
// results still show. That one has not been deleted in 9.4.1 — it would still run — but AGP's own
// `AbstractAppExtension.applicationVariants` getter reports itself deprecated the moment it is
// touched, naming `AndroidComponentsExtension` (this block) as the replacement and classifying
// itself as `LEGACY_VARIANT_API`. Writing the deprecated half of that pair into a new change, on a
// repository that has already paid once for AGP removing a legacy surface out from under a plugin
// (`gradle/libs.versions.toml`, `hilt`), would be choosing the thing with a removal date.
// `VariantOutput.outputFileName` is a `Property<String>` in the current API, so it is `.set(...)`
// rather than the legacy API's plain assignment.
//
// Scoped to `release` deliberately: `debug`'s output name is referenced by nothing here, but
// `ci.yml`'s `build-test` job and every IDE run configuration assume AGP's default for it, and
// nothing about this item asked for that to move.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("AGO_Chat_v$agoReleaseVersion.apk")
        }
    }
}

kotlin {
    jvmToolchain(
        libs.versions.jdk
            .get()
            .toInt(),
    )
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
    // `26-17`: the Тема preference's own store — see `gradle/libs.versions.toml`'s own remarks on this
    // row for why it, and not Room or plain `SharedPreferences`.
    implementation(libs.androidx.datastore.preferences)

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
