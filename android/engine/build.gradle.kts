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

// The radio engine without Android: the app supplies the dongle (USB), the audio output, audio focus and the log
// through the interfaces in Ports.kt, so the engine runs (and is tested) on the JVM.
dependencies {
    api(project(":tuner-api"))
    api(project(":data"))
    api(project(":dab-core"))
    implementation(project(":logos"))                 // ImageSniff: slides are checked before anything decodes them
    implementation(project(":driver-dtr100"))
    implementation(project(":driver-si4684"))
    implementation(project(":driver-siano"))
    compileOnly(libs.orgjson)
    testImplementation(libs.orgjson)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
