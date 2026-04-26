import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
//import org.jetbrains.intellij.platform.gradle.TestFrameworkType
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

    // id("com.gradleup.shadow") version "9.4.1"
    // id("com.diffplug.spotless") version "6.25.0"
}

subprojects {
    apply(plugin = "org.jetbrains.intellij.platform.module")
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

//allprojects {
//    repositories {
//        mavenLocal()
//        maven { url = uri("https://jitpack.io") }
//        mavenCentral()
//        intellijPlatform {
//            defaultRepositories()
//        }
//        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
//    }
//    dependencies {
//        intellijPlatform {
//            intellijIdea(libs.versions.intellij.platform)
//        }
//    }
//}

//dependencies {
//    intellijPlatform {
//        bundledPlugin("com.intellij.java")
//
//        pluginModule(implementation(project(":shared")))
//        //      pluginModule(implementation(project(":frontend")))
//        //      pluginModule(implementation(project(":backend")))
//        //     testFramework(TestFrameworkType.Platform)
//    }
//}

//tasks {
//    runIde {
//        maxHeapSize = "4G"
//        // Enable internal mode and debug categories so QDLog debug appears during runIde
//        jvmArgs(
//            "-Didea.is.internal=true",
//            //   "-Didea.log.debug.categories=com.github.quanta_dance.quanta.plugins.intellij.*",
//            "-Djava.net.preferIPv4Stack=true",
//            "-Dnosplash=true",
//        )
//    }
//}


intellijPlatform {
    splitMode = true
    pluginInstallationTarget.set(SplitModeAware.PluginInstallationTarget.BOTH)

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, libs.versions.intellij.platform.get())
        }
    }
}

tasks {
    val moduleSources by configurations.registering

    // Add plugin open API sources to the plugin ZIP
    val sourcesJar by registering(Jar::class) {
        dependsOn(moduleSources)
        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        archiveClassifier.set(DocsType.SOURCES)
        from(sourceSets.main.map { it.allSource })
        from(provider {
            moduleSources.map {
                it.map { jarFile -> zipTree(jarFile) }
            }
        })
    }

    buildPlugin {
        dependsOn(sourcesJar)
        from(sourcesJar) { into("lib/src") }
    }

//    runIde {
//        maxHeapSize = "4G"
//        // Enable internal mode and debug categories so QDLog debug appears during runIde
//        jvmArgs(
//            "-Didea.is.internal=true",
//            //   "-Didea.log.debug.categories=com.github.quanta_dance.quanta.plugins.intellij.*",
//            "-Djava.net.preferIPv4Stack=true",
//            "-Dnosplash=true",
//        )
//    }

//    shadowJar {
//        // Handle very large shaded jars safely
//        isZip64 = true
//
//        // IntelliJ bundles a lot; ship our own shaded copies to avoid classpath conflicts
//        relocate("com.openai", "com.github.quanta_dance.quanta.shaded.com.openai")
//        relocate("javazoom", "com.github.quanta_dance.quanta.shaded.javazoom")
//      //  relocate("kotlinx.coroutines", "com.github.quanta_dance.quanta.shaded.kotlinx.coroutines")
//      //  relocate("kotlinx.io", "com.github.quanta_dance.quanta.shaded.kotlinx.io")
//        relocate("io.modelcontextprotocol", "com.github.quanta_dance.quanta.shaded.io.modelcontextprotocol")
//
//        dependencies {
//            // Exclude IntelliJ SDK dependencies from shading
//            exclude(dependency("com.intellij:.*"))
//        }
//    }

}
//tasks.named("buildPlugin") {
//    dependsOn("shadowJar")
//    //dependsOn("copyLicenses")
//}
