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
    testImplementation(libs.kotlin.test)
}

// Transfer-level golden tests replay local USB logs (captures/*.log, gitignored); skipped when absent.
tasks.test {
    systemProperty("dabplay.captures", rootDir.resolve("../captures").absolutePath)
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
