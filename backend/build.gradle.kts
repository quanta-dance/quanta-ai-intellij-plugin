import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("rpc")
    id("org.jetbrains.intellij.platform.module") // Handles subproject integration natively
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
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledModule("intellij.platform.backend")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
        testFramework(TestFrameworkType.Platform)
    }

    compileOnly(project(":shared"))

    // Plain standard implementations. No complex shadow configs needed here.
    implementation(libs.kotlin.serialization.core.jvm)
    implementation(libs.kotlin.serialization.json.jvm)
    implementation(libs.openai)
    implementation("com.openai:openai-java-client-okhttp:4.31.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.12.0")
    implementation(libs.ktor.client.java)

    quantaRuntime(libs.kotlin.serialization.core.jvm)
    quantaRuntime(libs.kotlin.serialization.json.jvm)
    quantaRuntime(libs.openai)
    quantaRuntime("com.openai:openai-java-client-okhttp:4.31.0")
    quantaRuntime("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    quantaRuntime("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    quantaRuntime("io.modelcontextprotocol:kotlin-sdk-client:0.12.0")
    quantaRuntime(libs.ktor.client.java)

    testImplementation(kotlin("test"))
    testImplementation(kotlin("stdlib"))
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation(libs.byte.buddy)
    testImplementation(libs.byte.buddy.agent)
    testImplementation(project(":shared"))
}

kotlin {
    jvmToolchain(21)
}

tasks {
    withType<Test>().configureEach {
        jvmArgs("-Dkotlinx.coroutines.debug=off")
        systemProperty("kotlinx.coroutines.debug", "off")
    }

    // Package backend runtime libraries into the backend module jar so split-mode runIde has the
    // required domain classes (OpenAI, MCP, JGit), but avoid bundling common infrastructure jars
    // that can conflict with the IDE/runtime classpath.
    named<Jar>("jar") {
        archiveClassifier.set("")
        enabled = true
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        val excludedRuntimeJarMarkers = listOf(
            "slf4j",
            "kotlinx-coroutines",
            "kotlinx-serialization",
            "ktor-",
            "netty",
        )

        val runtimeFiles = quantaRuntime
            .filter { file ->
                file.name.endsWith(".jar") &&
                        excludedRuntimeJarMarkers.none { marker -> file.name.contains(marker, ignoreCase = true) }
            }
            .map { zipTree(it) }
        from(runtimeFiles)
    }
}
