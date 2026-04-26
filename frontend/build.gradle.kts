import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("rpc")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")

}

val openAiRuntime by configurations.creating

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
    implementation(libs.openai)
    implementation("com.openai:openai-java-client-okhttp:4.31.0")
    openAiRuntime(libs.openai)
    openAiRuntime("com.openai:openai-java-client-okhttp:4.31.0")
    implementation(libs.javazoom)
}

kotlin {
    jvmToolchain(21)
}
//
//tasks {
//    withType<PrepareSandboxTask> {
//        runtimeClasspath.from(openAiRuntime)
//    }
//
//    withType<JavaCompile> {
//        sourceCompatibility = "21"
//        targetCompatibility = "21"
//    }
//
//    withType<org.gradle.jvm.tasks.Jar>().configureEach {
//        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
//        from(
//            openAiRuntime
//                .filter { it.name.endsWith(".jar") }
//                .map { zipTree(it) },
//        )
//    }
//}
