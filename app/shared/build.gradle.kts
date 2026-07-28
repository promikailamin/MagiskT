// :shared module - Java-only library shared between :core and :stub.
// Contains minimal shared logic without AndroidX/Kotlin dependencies.
plugins {
    alias(libs.plugins.android.library)
}

setupCommon()

android {
    namespace = "pro.magisk.shared"
    enableKotlin = false
}
