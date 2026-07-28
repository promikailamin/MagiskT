// Root project build script for the Magisk Android app.
// Declares all required Gradle plugins (applied in subprojects) and a root-level clean task.

plugins {
    id("MagiskPlugin")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.legacy.kapt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.navigation.safeargs) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.moshix) apply false
    alias(libs.plugins.lsparanoid) apply false
}

// Root clean task that deletes the top-level build dir and cascades to all subprojects
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)

    subprojects.forEach {
        dependsOn(":${it.name}:clean")
    }
}
