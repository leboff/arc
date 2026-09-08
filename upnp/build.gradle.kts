import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// ARCHITECTURE.md §3: :upnp is a pure java-library. It must never depend on
// android.* — the `checkNoAndroidImports` task wired in the root build enforces it.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
