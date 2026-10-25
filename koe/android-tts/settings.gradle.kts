// Ensimmäinen koe (docs/ANDROID.md): toimiiko välitys toiseen puhemoottoriin.
// Kertakäyttöinen. Repo- vai kansiopäätös tehdään kokeen jälkeen.

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

rootProject.name = "lausu-tts-koe"

include(":app")
