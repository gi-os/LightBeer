plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gios.lightbeer"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.gios.lightbeer"
        minSdk = 29
        targetSdk = 35
        // CI overwrites both from the workflow run number; see .github/workflows/build.yml
        versionCode = 1
        versionName = "2.2.0"

        // The LPIII is arm64 only; shipping four ABIs tripled the APK for nothing.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/lightbeer.jks")
            storePassword = "lightbeer"
            keyAlias = "lightbeer"
            keyPassword = "lightbeer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same committed key as debug, so either APK upgrades over the other.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // The bundled pour is a short all-intra clip so seeking is frame-accurate; that trades
    // away compression efficiency, so it's excluded from further (lossy) app compression.
    androidResources {
        noCompress += "mp4"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Video-scrubbed pour: frame-accurate seekTo(). Pinned at 1.4.x on purpose — 1.10.x's
    // AAR metadata demands compileSdk 36, which is past what AGP 8.7.3 (and this project's
    // compileSdk 35) supports. media3-ui is deliberately not a dependency: rendering goes
    // through a raw TextureView instead of PlayerView (see ui/BeerScreen.kt for why).
    implementation("androidx.media3:media3-exoplayer:1.4.1")
}
