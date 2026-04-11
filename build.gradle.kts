import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

group = "com.github.quanta_dance"

plugins {
    application
    id("java")
    alias(libs.plugins.intellij.platform)

    alias(libs.plugins.rpc) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false

    id("com.gradleup.shadow") version "9.4.1"
    id("com.diffplug.spotless") version "6.25.0"
}

subprojects {
    apply(plugin = "org.jetbrains.intellij.platform.module")
}

allprojects {
    repositories {
        mavenLocal()
        maven { url = uri("https://jitpack.io") }
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.platform)

        pluginModule(project(":shared"))
        pluginModule(project(":frontend"))
        pluginModule(project(":backend"))
        testFramework(TestFrameworkType.Platform)
    }

    implementation(libs.javazoom)
}

tasks {
    runIde {
        maxHeapSize = "4G"
        // Enable internal mode and debug categories so QDLog debug appears during runIde
        jvmArgs(
            "-Didea.is.internal=true",
            //   "-Didea.log.debug.categories=com.github.quanta_dance.quanta.plugins.intellij.*",
            "-Djava.net.preferIPv4Stack=true",
            "-Dnosplash=true",
        )
    }

    shadowJar {
        isZip64 = true
        relocate("javazoom", "com.github.quanta_dance.quanta.shaded.javazoom")
        relocate("vavi", "com.github.quanta_dance.quanta.shaded.vavi")
    }

    buildPlugin {
        dependsOn(shadowJar)
    }
}


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
    runIde {
        maxHeapSize = "4G"
        // Enable internal mode and debug categories so QDLog debug appears during runIde
        jvmArgs(
            "-Didea.is.internal=true",
            //   "-Didea.log.debug.categories=com.github.quanta_dance.quanta.plugins.intellij.*",
            "-Djava.net.preferIPv4Stack=true",
            "-Dnosplash=true",
        )
    }

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
