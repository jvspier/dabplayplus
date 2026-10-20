plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "io.github.jvspier.dabplay"
    compileSdk = 37

    defaultConfig {
        // Final (owner, 2026-10-04): the applicationId can never change once APKs or a Play release are out.
        applicationId = "io.github.jvspier.dabplay"
        minSdk = 26
        targetSdk = 36
        versionCode = 7                                 // +1 for every release: Android installs only higher codes as updates
        versionName = "1.3.0"
    }

    // The release key lives outside the repo: dabplay.storeFile and dabplay.password in ~/.gradle/gradle.properties.
    // Without them (CI, other machines) release builds are signed with the debug key: fine for testing, never published.
    val releaseStore = providers.gradleProperty("dabplay.storeFile").orNull
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storePassword = providers.gradleProperty("dabplay.password").get()
            keyAlias = "dabplay"
            keyPassword = storePassword
        }
    }

    buildTypes {
        release {
            // R8: Compose is only smooth when minified and optimised (debug builds jank by design)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    implementation(project(":driver-si4684"))
    implementation(project(":driver-siano"))
    implementation(project(":logos"))
    implementation(project(":usb-android"))
    implementation(project(":audio"))
    implementation(project(":data"))
    implementation(project(":engine"))

    // baseline profile (src/release/generated/baselineProfiles): the UI is compiled ahead of time at install, not
    // interpreted until the device finds idle time to compile it, which a head unit that is always in use rarely has
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
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
    testImplementation(libs.kotlin.test)
}
