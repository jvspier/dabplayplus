plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.jvspier.dabplay.usb"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":tuner-api"))
}
