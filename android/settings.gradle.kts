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
include(":dab-core", ":tuner-api", ":driver-dtr100", ":driver-replay", ":logos")
// Android
include(":usb-android", ":audio", ":data", ":app")
