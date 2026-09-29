pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DABplay"

// Pure Kotlin/JVM (unit-testable on the laptop)
include(":dab-core", ":tuner-api", ":driver-dtr100", ":logos")
// Android
include(":usb-android", ":audio", ":data", ":app")
// Generates the app's baseline profile on a connected device/emulator: ./gradlew :app:generateReleaseBaselineProfile
include(":baselineprofile")
