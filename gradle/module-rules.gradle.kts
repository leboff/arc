// Fails the build if a JVM-pure module references android.* (or other Android-only
// APIs that must not leak into the protocol layer). Applied from the root build.
//
// Rationale: docs/ARCHITECTURE.md §3 — ":upnp being a java-library (not
// com.android.library) is deliberate and load-bearing". This check makes the
// boundary a build failure rather than a code-review note.

val jvmPureModules = setOf(":upnp")

// Regexes that indicate an Android dependency crept in.
val forbiddenPatterns = listOf(
    Regex("""^\s*import\s+android\."""),
    Regex("""^\s*import\s+androidx\."""),
    Regex("""^\s*import\s+com\.google\.android\."""),
    Regex("""^\s*import\s+dalvik\."""),
)

gradle.projectsEvaluated {
    jvmPureModules.forEach { path ->
        val project = rootProject.findProject(path) ?: return@forEach

        val checkNoAndroidImports = project.tasks.register("checkNoAndroidImports") {
            group = "verification"
            description = "Fails if ${project.path} references android.* / androidx.*"

            val sourceDirs = listOf(
                project.file("src/main/java"),
                project.file("src/main/kotlin"),
            ).filter { it.exists() }
            inputs.files(project.fileTree("src/main"))

            doLast {
                val violations = mutableListOf<String>()
                sourceDirs.forEach { dir ->
                    dir.walkTopDown()
                        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                        .forEach { file ->
                            file.readLines().forEachIndexed { idx, line ->
                                if (forbiddenPatterns.any { it.containsMatchIn(line) }) {
                                    violations += "${file.relativeTo(project.projectDir)}:${idx + 1}: $line"
                                }
                            }
                        }
                }
                if (violations.isNotEmpty()) {
                    throw GradleException(
                        buildString {
                            appendLine("${project.path} must not depend on Android APIs (ARCHITECTURE.md §3):")
                            violations.forEach { appendLine("  $it") }
                        },
                    )
                }
            }
        }

        project.tasks.matching { it.name == "check" }.configureEach {
            dependsOn(checkNoAndroidImports)
        }
    }
}
