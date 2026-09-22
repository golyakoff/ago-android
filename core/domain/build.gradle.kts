// Plain Kotlin JVM module — deliberately no Android Gradle plugin at all. That absence is the
// load-bearing property `adr/0178` and `26-07`'s own Scope name: with no `com.android.library`
// applied, there is no `android.*` / `androidx.*` classpath in this module at all, so
// `import android.content.Context` fails to compile here rather than merely being disallowed by
// convention. See `ago-android/docs/architecture.md`, "Module layout".
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
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
    // `26-12`: nothing on the main classpath. `suspend` needs no dependency — it is a stdlib-level
    // language feature — so the ports in `identity/` stay a module with literally no third-party
    // code behind them, which is the property `docs/architecture.md` names for this module.
    // `kotlinx-coroutines-test` is test-only, for `runTest` around those suspending ports.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
