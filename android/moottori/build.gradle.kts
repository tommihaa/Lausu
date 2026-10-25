plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fi.tommi.lausu.moottori"
    compileSdk = 36

    defaultConfig {
        applicationId = "fi.tommi.lausu"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    // Data pakataan suoraan lisäosan kansiosta, jotta Android ja NVDA käyttävät samaa tiedostoa.
    sourceSets["main"].assets.srcDir(rootProject.file("../addon/globalPlugins/lausu/data"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":kerros"))
}
