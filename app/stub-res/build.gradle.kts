// :stub-res module - Resources-only APK used by the stub for minimal asset bundling.
plugins {
    alias(libs.plugins.android.application)
}

setupCommon()

android {
    namespace = "pro.magisk"
    enableKotlin = false

    buildTypes {
        release {
            isShrinkResources = false
        }
    }
}
