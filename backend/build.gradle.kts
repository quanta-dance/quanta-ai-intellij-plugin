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
    exclude(group = "io.ktor")
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
    compileOnly(libs.ktor.client.java)

    // Third-party libs needed at runtime in the backend process — added to backendRuntime
    // so they get bundled into the backend module JAR below
    compileOnly(libs.openai)
    compileOnly(libs.openai.okhttp)
    compileOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    compileOnly("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    compileOnly("io.modelcontextprotocol:kotlin-sdk-client:0.12.0")

    backendRuntime(libs.openai)
    backendRuntime(libs.openai.okhttp)
    backendRuntime("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    backendRuntime("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    backendRuntime("io.modelcontextprotocol:kotlin-sdk-client:0.12.0")

    testImplementation(libs.openai)
    testImplementation(libs.openai.okhttp)
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    testImplementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    testImplementation("io.modelcontextprotocol:kotlin-sdk-client:0.12.0")
    testImplementation(libs.ktor.client.java)
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

    named<Jar>("jar") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(backendRuntime.filter { it.name.endsWith(".jar") }.map { zipTree(it) })
    }
}
