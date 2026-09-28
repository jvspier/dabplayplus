plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.jvspier.dabplay.data"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.orgjson)          // the real org.json: android.jar's copy is only stubs in unit tests
}
