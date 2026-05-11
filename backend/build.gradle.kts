import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
   // id("org.jetbrains.intellij.platform.module")
    id("rpc")
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

val quantaRuntime by configurations.creating


dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.platform)

        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledModule("intellij.platform.backend")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
        testFramework(TestFrameworkType.Platform)
    }

    compileOnly(project(":shared"))

    implementation(libs.kotlin.serialization.core.jvm)
    implementation(libs.kotlin.serialization.json.jvm)
    implementation(libs.openai)
    implementation("com.openai:openai-java-client-okhttp:4.31.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")

    quantaRuntime(libs.openai)
    quantaRuntime("com.openai:openai-java-client-okhttp:4.31.0")
    quantaRuntime("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")

    // MCP SDK: BOM for alignment and then the specific SDK
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:0.14.0"))
    implementation("io.modelcontextprotocol:kotlin-sdk:0.7.2")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("stdlib"))
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation(libs.byte.buddy)
    testImplementation(libs.byte.buddy.agent)
    testImplementation(project(":shared"))
}

kotlin {
    // This single line replaces both the 'java' and 'kotlin' toolchain blocks
    jvmToolchain(21)
}
//
//tasks.withType<PrepareSandboxTask> {
//    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
//    into("lib") {
//        from(
//            configurations.runtimeClasspath
//                .filter { it.name.endsWith(".jar") }
//                .map { zipTree(it) }
//        )
//    }
//}

// Optional: Only keep this if you need to build a fat JAR specifically.
// Otherwise, the IntelliJ Platform plugin handles JAR creation for you.
tasks {
    withType<Test>().configureEach {
        jvmArgs("-Dkotlinx.coroutines.debug=off")
        systemProperty("kotlinx.coroutines.debug", "off")
    }

    withType<Jar>().configureEach {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        val runtimeFiles = quantaRuntime
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
        from(runtimeFiles)
    }
}
