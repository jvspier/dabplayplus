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
    testImplementation(libs.kotlin.test)
}

// Golden tests read local captures (gitignored); they are skipped when the files are absent.
tasks.test {
    systemProperty("dabplay.captures", rootDir.resolve("../captures").absolutePath)
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
