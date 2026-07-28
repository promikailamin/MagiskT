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
// App modules (two UI variants), shared library, core library, stub APK, and stub resources
include(":apk", ":apkT", ":core", ":shared", ":stub", ":stub-res")
