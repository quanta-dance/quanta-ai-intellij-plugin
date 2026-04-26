rootProject.name = "intellij-quanta-ai-plugin"

include(":shared")
include(":frontend")
include(":backend")

pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    }
}

