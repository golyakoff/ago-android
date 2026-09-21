// Root build file. Plugins are declared here with `apply false` so their version is resolved
// once (from the version catalog) and applied per-module where actually needed — the same
// "one place a version is declared" rule the version catalog itself states.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
