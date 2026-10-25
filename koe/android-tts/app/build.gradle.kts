plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fi.tommi.lausu.koe"
    compileSdk = 36

    defaultConfig {
        applicationId = "fi.tommi.lausu.koe"
        minSdk = 30 // synthesizeToFile(ParcelFileDescriptor) tuli API 30:ssä
        targetSdk = 36
        versionCode = 1
        versionName = "koe"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}
