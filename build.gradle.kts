import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

fun latestChangeNotesFromChangelog(): String {
    val changelog = file("CHANGELOG.md").takeIf { it.exists() }?.readText().orEmpty()
    if (changelog.isBlank()) return ""

    val lines = changelog.lines()
    val startIndex = lines.indexOfFirst { it.startsWith("## [") }
    if (startIndex < 0) return ""
    val endIndex = lines.drop(startIndex + 1).indexOfFirst { it.startsWith("## [") }
        .let { if (it < 0) lines.size else startIndex + 1 + it }
    return lines.subList(startIndex + 1, endIndex)
        .joinToString("\n")
        .trim()
}

group = "com.github.quanta_dance"

plugins {
    java
    alias(libs.plugins.kotlin) apply false
    application
    alias(libs.plugins.intellij.platform)
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.rpc) apply false
    id("com.diffplug.spotless") version "8.5.1"
}

subprojects {
    apply(plugin = "org.jetbrains.intellij.platform.module")

    // Apply Spotless to modules that use the Kotlin JVM plugin. The Spotless plugin itself
    // is declared in the root plugins block (apply false) so we only enable it where needed.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "com.diffplug.spotless")
    }

    // Configure Spotless when it is present on the project
    plugins.withId("com.diffplug.spotless") {
        spotless {
            kotlin {
                licenseHeaderFile(rootProject.file("config/license/HEADER"))
                ktlint()
                trimTrailingWhitespace()
                endWithNewline()
            }
        }
    }
}


repositories {
    mavenLocal()
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.platform)
        bundledPlugin("com.intellij.java")
        pluginModule(implementation(project(":shared")))
        pluginModule(implementation(project(":backend")))
        pluginModule(implementation(project(":frontend")))
    }

    implementation(project(path = ":backend", configuration = "runtimeElements"))
}

intellijPlatform {
    buildSearchableOptions = false
    splitMode = true
    pluginInstallationTarget.set(SplitModeAware.PluginInstallationTarget.BOTH)

    pluginVerification {
        ides {
            create(
                IntelliJPlatformType.IntellijIdeaUltimate,
                libs.versions.intellij.platform.get(),
            )
        }
    }
}

tasks.register<Copy>("copyLicenses") {
    from("LICENSE.txt", "NOTICE.txt", "licenses")
    into(layout.buildDirectory.dir("distributions/licenses"))
}

tasks {
    val moduleSources by configurations.registering

    // Add plugin open API sources to the plugin ZIP
    val sourcesJar by registering(Jar::class) {
        dependsOn(moduleSources)
        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        archiveClassifier.set(DocsType.SOURCES)
        from(sourceSets.main.map { it.allSource })
        from(
            provider {
                moduleSources.map {
                    it.map { jarFile -> zipTree(jarFile) }
                }
            },
        )
    }

    buildPlugin {
        dependsOn(sourcesJar)
        from(sourcesJar) { into("lib/src") }
    }

    patchPluginXml {
        version = project.version.toString()
        sinceBuild.set("252")
        untilBuild.set("271.*")
        changeNotes.set(latestChangeNotesFromChangelog())
    }

    signPlugin {
        enabled = false
    }

    publishPlugin {
        enabled = true
        token = System.getenv("JETBRAINS_API_TOKEN")
    }

    test {
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = false
            showStackTraces = false
        }
    }
}

tasks.named("buildPlugin") {
    dependsOn("copyLicenses")
}


tasks {
    runIdeBackend {
        splitModeServerPort.set(12345)
    }
}

tasks.named<JavaExec>("runIde") {
    dependsOn(":backend:processResources")
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Dnosplash=true")
    }
}