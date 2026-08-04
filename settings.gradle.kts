rootProject.name = "designless-kotlin"

// Repositories are declared once, here, rather than per-module. A module that
// declares its own is a module that can silently resolve a dependency from
// somewhere the rest of the build does not use.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

include("serve")
include("serve-android")
