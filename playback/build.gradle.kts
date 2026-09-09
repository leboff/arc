plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Phase 4 (docs/PLAN.md §4): the Media3 playback engine, the video-to-GL bridge
// contract, resume storage, the scrub controller and the format-fallback policy.

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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    api(project(":vrcore"))
    api(project(":upnp"))

    api(libs.media3.exoplayer)
    api(libs.media3.common)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    // Phase F2 (docs/FORMAT_SUPPORT_PLAN.md §5): LibVLC compatibility engine for
    // containers/codecs Media3 cannot demux or decode (WMV/ASF, RealMedia, VC-1,
    // MPEG-2, malformed MP4). Resolved from mavenCentral(); no NDK, no build step.
    implementation(libs.libvlc.all)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
