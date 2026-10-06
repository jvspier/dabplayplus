plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Plain Kotlin (station store, logo store, ServiceKey), so the engine and its tests can use it on the JVM.
// SettingsStore needs SharedPreferences and lives in :app (same package).
dependencies {
    api(libs.kotlinx.coroutines.core)
    compileOnly(libs.orgjson)          // part of the Android platform; only needed on the JVM for tests
    testImplementation(libs.orgjson)
    testImplementation(libs.kotlin.test)
}
