plugins {
    id("rpc")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")

}

val quantaRuntime by configurations.creating

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.platform)
        bundledModule("intellij.platform.frontend")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")

        implementation(libs.kotlin.serialization.core.jvm)
        implementation(libs.kotlin.serialization.json.jvm)

        composeUI()
    }

    compileOnly(project(":shared"))
    implementation(libs.javazoom)

    quantaRuntime(libs.javazoom)
}

kotlin {
    jvmToolchain(21)
}

tasks {
    withType<Jar>().configureEach {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        val runtimeFiles = quantaRuntime
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
        from(runtimeFiles)
    }
}
