plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Version metadata is derived from CI-provided environment/properties so every
 * tagged release embeds a matching versionName, rather than the previous
 * hardcoded "0.1.0-phase1" placeholder. Falls back to a local dev value when
 * building outside CI (e.g. `./gradlew assembleDebug` on a workstation).
 *
 *  - ARC_VERSION_NAME: the git tag with the leading "v" stripped (e.g. "0.9.3").
 *  - ARC_VERSION_CODE: a strictly increasing integer for the Play/APK versionCode,
 *    supplied by CI as the GitHub Actions run number so it always increases.
 */
val resolvedVersionName: String =
    (project.findProperty("ARC_VERSION_NAME") as String?)
        ?: System.getenv("ARC_VERSION_NAME")
        ?: "0.0.0-dev"

val resolvedVersionCode: Int =
    ((project.findProperty("ARC_VERSION_CODE") as String?) ?: System.getenv("ARC_VERSION_CODE"))
        ?.toIntOrNull()
        ?: 1

android {
    namespace = "com.daydreamvr.player"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.daydreamvr.player"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Stable release signing identity (ARCHITECTURE review C7): reads the
        // keystore + credentials from environment variables so the same key is
        // used for every CI build and every release. When those env vars are
        // absent (local dev build), release falls back to the AGP debug
        // keystore so `./gradlew assembleRelease` still works on a workstation
        // without needing release secrets.
        create("release") {
            val storeFilePath = System.getenv("ARC_RELEASE_KEYSTORE_PATH")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("ARC_RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ARC_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("ARC_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 full mode (ARCHITECTURE.md §18); keep rules in proguard-rules.pro.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Use the stable release key when configured (CI / release builds);
            // otherwise fall back to the AGP debug keystore for local dev builds.
            signingConfig = if (System.getenv("ARC_RELEASE_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":vrcore"))
    implementation(project(":upnp"))
    implementation(project(":playback"))
    implementation(files(rootProject.file("playback/libs/media3-decoder-ffmpeg-1.4.1.aar")))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
