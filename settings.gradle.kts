pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ago-android"

// Dependency direction, enforced by which modules are even declared here and by what each
// module's own build file is allowed to depend on (see each build.gradle.kts):
//   :app -> :core:network -> :core:domain
// :core:domain depends on neither of the other two.
include(":app")
include(":core:network")
include(":core:domain")
