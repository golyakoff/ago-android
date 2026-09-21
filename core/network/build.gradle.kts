// Empty on purpose. Ktor, the SignalR client and token attachment arrive with their first real
// caller (26-12, 26-13) — an abstraction with one caller is a guess about the second
// (`ago-android/docs/architecture.md`, `26-07`'s own Scope). What exists here today is only the
// module boundary itself: an Android library that depends on `:core:domain` and nothing else,
// so `:app` never has to reach past it to talk to `:core:domain` directly.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ago.chat.android.core.network"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    explicitApi()
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // The one dependency this module is allowed: :core:domain, never the other direction.
    api(project(":core:domain"))
}
