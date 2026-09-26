// Settings file for the Magisk multi-module Android project.
// Configures dependency repositories, plugin management, and module inclusion.

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

pluginManagement {
    // Include the convention plugin (build_logic) for shared build configuration
    includeBuild("build_logic")
    repositories {
        gradlePluginPortal()
        google()
    }
}

rootProject.name = "Magisk"
// App modules (apk UI variant) and core library
include(":apk", ":core")
