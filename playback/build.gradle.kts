plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Phase 1 stub. The Media3 wrapper and the video-to-GL-texture bridge land in
// Phase 4 (docs/PLAN.md §4); this module exists now only so the dependency graph
// (app -> playback -> media3) is wired from the start.

android {
    namespace = "com.daydreamvr.playback"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":vrcore"))
    api(libs.media3.exoplayer)
    api(libs.media3.common)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
