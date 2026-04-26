pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

// Provides declared Java toolchain download repositories.
// This avoids Gradle's deprecation warning about auto-provisioned toolchains without repositories
// and will prevent build failures on Gradle 10+.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "intellij-quanta-ai-plugin"

// dependencyResolutionManagement {
//    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
//    repositories {
//        mavenCentral()
//        maven { url = uri("https://jitpack.io") }
//    }
// }

