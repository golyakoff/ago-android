// `26-12` is the first real caller `26-07` was waiting for: the Ktor client, the bearer-token
// attachment and the `X-Ago-Active-Site` plugin all live here now. `26-13` adds the SignalR client
// and the operator hub connection holder — the last of the three real callers this module was
// scaffolded for.
plugins {
    // `25-214`: `org.jetbrains.kotlin.android` is deliberately absent — see `app/build.gradle.kts`
    // for why (AGP 9's built-in Kotlin makes applying it a build failure).
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "ago.chat.android.core.network"

    // `25-214`: kept identical to `:app`'s own `compileSdk` on purpose — two Android modules
    // compiling against different platform jars is a difference that only ever surfaces as a
    // confusing link error. See `app/build.gradle.kts` for why the number is 37.
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(
        libs.versions.jdk
            .get()
            .toInt(),
    )
    explicitApi()
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // The one project dependency this module is allowed: :core:domain, never the other direction.
    // `api`, not `implementation`, because :app consumes `identity.Tenancy`/`SignInDestination`
    // through the types this module exposes.
    api(project(":core:domain"))

    // `api` on ktor-client-core for the same reason: `createAgoHttpClient` returns an `HttpClient`,
    // so :app needs the type on its own compile classpath to hold one. Everything else is an
    // implementation detail of how this module talks to the wire.
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // `26-13`: the operator hub connection. `signalr` is Microsoft's own Java client (`adr/0178`);
    // `rxjava3` is on the compile classpath explicitly because this module's own code names its
    // `Single`/`Completable` types directly, not only because `signalr` pulls it in transitively;
    // `kotlinx-coroutines-rx3` is the one bridge between that shape and this project's own
    // `suspend`-everywhere convention (`AccessTokenProvider`'s own doc comment).
    implementation(libs.signalr)
    implementation(libs.rxjava3)
    implementation(libs.kotlinx.coroutines.rx3)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
