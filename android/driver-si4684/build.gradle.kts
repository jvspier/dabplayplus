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

dependencies {
    api(project(":tuner-api"))
    implementation(project(":dab-core"))      // DAB text charsets
    testImplementation(libs.kotlin.test)
}

tasks.test {
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
