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
        // `26-06`/`adr/0180`: RuStore Push's own artifacts. **Only this address** - RuStore's docs
        // say the older `artifactory-external.vkpartner.ru` "may stop working at some point", and
        // `dartway/dartway#263` (a real report, not a hypothetical) says that repository was retired
        // 2026-10-01. Nothing else on this project's dependency graph comes from RuStore, so this is
        // its one call site.
        maven {
            url = uri("https://nexus-external.rustore.ru/repository/maven-rustore-exposed/")
        }
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
