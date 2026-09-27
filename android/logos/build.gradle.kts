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
    api(libs.okhttp)
    implementation(libs.dnsjava)
    compileOnly(libs.orgjson)          // part of the Android platform; only needed on the JVM for tests
    testImplementation(libs.orgjson)
    testImplementation(libs.kotlin.test)
}
