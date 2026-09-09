import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// ARCHITECTURE.md §3: :upnp is a pure java-library. It must never depend on
// android.* — the `checkNoAndroidImports` task wired in the root build enforces it.
//
// The whole protocol layer (SSDP, device description, SOAP, DIDL-Lite) runs in
// millisecond-scale JVM tests against MockWebServer, with no emulator in the loop.
// XML parsing uses the JDK's javax.xml (hardened in net/SafeXml.kt); the plan's
// original "XmlPullParser" note is satisfied by a JDK DOM parser so the module
// stays dependency-light and Android-free.

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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
