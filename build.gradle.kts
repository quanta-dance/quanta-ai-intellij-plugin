import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

group = "com.github.quanta_dance"

plugins {
    java
    alias(libs.plugins.kotlin) apply false
    application
    alias(libs.plugins.intellij.platform)
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.rpc) apply false
    id("com.diffplug.spotless") version "8.4.0"
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
}

intellijPlatform {
    splitMode = true
    pluginInstallationTarget.set(SplitModeAware.PluginInstallationTarget.BOTH)

    pluginVerification {
        ides {
            create(
                IntelliJPlatformType.IntellijIdeaCommunity,
                libs.versions.intellij.platform
                    .get(),
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
}

tasks.named("buildPlugin") {
    dependsOn("copyLicenses")
}


