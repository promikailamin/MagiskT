plugins {
    id("com.android.application")
}

setupMainApk()

android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(project(":core"))
    coreLibraryDesugaring(libs.jdk.libs)
    implementation(libs.appcompat)
}
