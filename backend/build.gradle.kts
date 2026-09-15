import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("rpc")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// In split-mode the backend runs in a separate JVM process with its own classpath.
// The root plugin's lib/ directory is NOT on the backend process classpath — only
// lib/modules/intellij-quanta-ai-plugin.backend.jar is loaded by the backend kernel.
// Therefore all third-party deps must be bundled inside the backend module JAR (fat JAR).
val backendRuntime by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    // Exclude deps that the backend kernel process already provides
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json-jvm")
    exclude(group = "org.slf4j")
    exclude(group = "ch.qos.logback")
    exclude(group = "io.netty")
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

    // Provided by the IDE at runtime — compile against them but do not bundle
    compileOnly(libs.kotlin.serialization.core.jvm)
    compileOnly(libs.kotlin.serialization.json.jvm)

    // Third-party libs needed at runtime in the backend process — added to backendRuntime
    // so they get bundled into the backend module JAR below
    compileOnly(libs.openai)
    compileOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    compileOnly("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    compileOnly(platform("io.modelcontextprotocol.sdk:mcp-bom:2.0.1"))
    compileOnly("io.modelcontextprotocol.sdk:mcp") {
        // The default JSON provider is Jackson 3 (`tools.jackson.*`), which the IDE does not provide.
        exclude(group = "io.modelcontextprotocol.sdk", module = "mcp-json-jackson3")
    }
    compileOnly("io.modelcontextprotocol.sdk:mcp-json-jackson2")

    backendRuntime(libs.openai)
    backendRuntime("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    backendRuntime("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    backendRuntime(platform("io.modelcontextprotocol.sdk:mcp-bom:2.0.1"))
    backendRuntime("io.modelcontextprotocol.sdk:mcp") {
        // IntelliJ ships Jackson 2 (`com.fasterxml.jackson.*`), so use that SDK provider instead.
        exclude(group = "io.modelcontextprotocol.sdk", module = "mcp-json-jackson3")
    }
    backendRuntime("io.modelcontextprotocol.sdk:mcp-json-jackson2")

    testImplementation(libs.openai)
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    testImplementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    testImplementation(platform("io.modelcontextprotocol.sdk:mcp-bom:2.0.1"))
    testImplementation("io.modelcontextprotocol.sdk:mcp") {
        exclude(group = "io.modelcontextprotocol.sdk", module = "mcp-json-jackson3")
    }
    testImplementation("io.modelcontextprotocol.sdk:mcp-json-jackson2")
    testImplementation(kotlin("test"))
    testImplementation(kotlin("stdlib"))
    testImplementation("io.mockk:mockk:1.13.12")
    // Match the IntelliJ Platform's coroutine debug agent (1.10.2). MockK otherwise
    // resolves coroutines-core 1.6.4, which crashes the agent before tests start.
    testRuntimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.10.2")
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

    named<Jar>("jar") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(
            provider {
                backendRuntime
                    .resolvedConfiguration
                    .resolvedArtifacts
                    .filter { it.file.name.endsWith(".jar") }
                    // Exclude JARs not needed at runtime — we use OpenAIJdkHttpClient
                    .filter { !it.file.name.startsWith("openai-java-client-okhttp") }
                    .filter { !it.file.name.startsWith("okhttp") }
                    .filter { !it.file.name.startsWith("okio") }
                    // jackson-core is provided by the IDE platform; bundling it adds shaded
                    // FastDoubleParse classes that reference VarHandle.set unavailable on older JVMs
                    .filter { !it.file.name.startsWith("jackson-core") }
                    .map {
                        zipTree(it.file).matching {
                            // Android TLS stubs — dead code on JVM
                            exclude("android/**")
                            // kotlin-logging-jvm's logback integration references ch.qos.logback
                            // which is not bundled in the IDE; exclude the whole logback sub-package
                            exclude("io/github/oshai/kotlinlogging/logback/**")
                        }
                    }
            },
        )
    }
}
