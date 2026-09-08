plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Enforce the architecture's module boundary: :upnp must never touch android.*
// (see docs/ARCHITECTURE.md §3). The rule lives in a standalone script so it can
// be applied to any module that is meant to stay JVM-pure.
apply(from = "gradle/module-rules.gradle.kts")

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
