// Lausu Androidille (docs/ANDROID.md). Kerros on pelkkää JVM-Kotlinia, jotta sen testit
// ajetaan ilman laitetta. Moottori on Android-sovellus, joka käyttää kerrosta.

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lausu-android"

include(":kerros")
include(":moottori")
