plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.jvspier.dabplay.audio"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":dab-core"))
    implementation(project(":engine"))          // AudioOut, the interface the engine plays through
}
