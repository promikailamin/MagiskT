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
