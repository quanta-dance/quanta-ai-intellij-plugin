pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    }
}

rootProject.name = "intellij-quanta-ai-plugin"

include(":shared")
include(":frontend")
include(":backend")

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

