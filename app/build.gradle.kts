plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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

        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // modules together and is the only place DI will be wired later (26-12).
    implementation(project(":core:network"))
    implementation(project(":core:domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
