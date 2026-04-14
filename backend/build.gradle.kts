import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("rpc")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val openAiRuntime by configurations.creating

configurations.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json-jvm")
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.platform)

        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledModule("intellij.platform.backend")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
    }

    implementation(project(":shared"))
    implementation(libs.openai)
    implementation("com.openai:openai-java-client-okhttp:4.31.0")
    openAiRuntime(libs.openai)
    openAiRuntime("com.openai:openai-java-client-okhttp:4.31.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    //   implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    // MCP SDK (use its BOM to keep modules aligned)
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:0.14.0"))
    implementation("io.modelcontextprotocol:kotlin-sdk:0.7.2")

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.12")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    withType<PrepareSandboxTask> {
        runtimeClasspath.from(openAiRuntime)
    }

    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    withType<org.gradle.jvm.tasks.Jar>().configureEach {
        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
        from(
            openAiRuntime
                .filter { it.name.endsWith(".jar") }
                .map { zipTree(it) },
        )
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("kotlinx/serialization/**")
    }
}
