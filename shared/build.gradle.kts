plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("rpc")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.platform)
    }

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlin.serialization.json.jvm)
}

kotlin {
    jvmToolchain(21)
}
