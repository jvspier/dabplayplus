plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.jvspier.dabplay"
    compileSdk = 37

    defaultConfig {
        // Placeholder: the applicationId is permanent once published on the Play Store.
        applicationId = "io.github.jvspier.dabplay"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8: Compose is only smooth when minified and optimised (debug builds jank by design)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // local testing only, until there is a real upload key
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":dab-core"))
    implementation(project(":tuner-api"))
    implementation(project(":driver-dtr100"))
    implementation(project(":logos"))
    implementation(project(":usb-android"))
    implementation(project(":audio"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.phosphor.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
