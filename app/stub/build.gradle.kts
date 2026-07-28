// :stub module - Thin proxy APK that downloads and classloads the real Magisk app.
// Uses LSParanoid for string obfuscation and is minified with ProGuard.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.lsparanoid)
}

lsparanoid {
    seed = if (RAND_SEED != 0) RAND_SEED else null
    includeDependencies = true
    classFilter = { true }
}

android {
    namespace = "pro.magisk"
    enableKotlin = false

    val base = "https://github.com/promikailamin/MagiskT/releases/download/"
    val url = base + "build/app-release.apk"

    defaultConfig {
        applicationId = "pro.magisk"
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "APK_URL", "\"$url\"")
        buildConfigField("int", "STUB_VERSION", Config.stubVersion)
    }

    buildTypes {
        release {
            proguardFiles("proguard-rules.pro")
            isMinifyEnabled = true
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

setupStubApk()

dependencies {
    implementation(project(":shared"))
}
