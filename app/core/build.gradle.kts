// :core module - Shared logic library for the Magisk apps.
// Provides DI (ServiceLocator), Room database, networking (OkHttp/Retrofit),
// flash/install logic, SU handling, and the download engine.
plugins {
    alias(libs.plugins.android.library)
    kotlin("plugin.parcelize")
    alias(libs.plugins.moshix)
    alias(libs.plugins.ksp)
    alias(libs.plugins.wire)
}

setupCoreLib()

ksp {
    arg("room.generateKotlin", "true")
}

wire {
    kotlin {}
}

android {
    namespace = "pro.magisk.core"

    defaultConfig {
        buildConfigField("String", "APP_PACKAGE_NAME", "\"pro.magisk\"")
        buildConfigField("int", "APP_VERSION_CODE", "${Config.versionCode}")
        buildConfigField("String", "APP_VERSION_NAME", "\"${Config.version}\"")
        buildConfigField("String", "BUILD_COMMIT", "\"${Config.buildCommit ?: "local"}\"")
        buildConfigField("int", "STUB_VERSION", Config.stub_version)
        consumerProguardFile("proguard-rules.pro")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    api(project(":shared"))
    coreLibraryDesugaring(libs.jdk.libs)

    api(libs.timber)
    api(libs.markwon.core)
    implementation(libs.bcpkix)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.wire.runtime)

    api(libs.libsu.core)
    api(libs.libsu.service)
    api(libs.libsu.nio)

    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.retrofit.scalars)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.dnsoverhttps)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.core.splashscreen)
    implementation(libs.core.ktx)
    implementation(libs.activity)
    implementation(libs.collection.ktx)
    implementation(libs.profileinstaller)

}
