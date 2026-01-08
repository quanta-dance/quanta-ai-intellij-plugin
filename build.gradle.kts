import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.diffplug.spotless") version "6.25.0"
    id("java")
    alias(libs.plugins.intellijPlatform)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.serialization)

    id("com.gradleup.shadow") version "9.2.2"
}

group = "com.github.quanta_dance"

repositories {
    mavenLocal()
    maven { url = uri("https://jitpack.io") }
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

// Ensure both Java and Kotlin compile to Java 17 bytecode
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Ensure IntelliJ Platform's kotlinx-coroutines wins on the test classpath to avoid NoSuchMethodError
configurations {
    named("testRuntimeClasspath") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-debug")
    }
}

tasks {

    shadowJar {
        // Handle very large shaded jars safely
        isZip64 = true

        // IntelliJ bundles a lot; ship our own shaded copies to avoid classpath conflicts
        relocate("io.ktor", "com.github.quanta_dance.quanta.shaded.io.ktor")
        relocate("kotlinx.coroutines", "com.github.quanta_dance.quanta.shaded.kotlinx.coroutines")
        relocate("kotlinx.io", "com.github.quanta_dance.quanta.shaded.kotlinx.io")
        relocate("io.modelcontextprotocol", "com.github.quanta_dance.quanta.shaded.io.modelcontextprotocol")

        dependencies {
            // Exclude IntelliJ SDK dependencies from shading
            exclude(dependency("com.intellij:.*"))
        }
    }

    patchPluginXml {
        version = project.version.toString()
        sinceBuild.set("252")
        untilBuild.set("271.*")
        changeNotes.set(
            """
            Initial version of Quanta AI plugin
            """.trimIndent(),
        )
    }

    signPlugin {
        enabled = false
    }

    publishPlugin {
        enabled = true
        token = System.getenv("JETBRAINS_API_TOKEN")
    }

    buildSearchableOptions {
        // https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin-faq.html#how-to-disable-building-searchable-options
        enabled = false
    }

    runIde {
        maxHeapSize = "4G"
        // Enable internal mode and debug categories so QDLog debug appears during runIde
        jvmArgs(
            "-Didea.is.internal=true",
            "-Didea.log.debug.categories=com.github.quanta_dance.quanta.plugins.intellij.*",
            "-Djava.net.preferIPv4Stack=true"
        )
    }

    test {
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = false
            showStackTraces = false
        }
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.2")
        bundledPlugins(
            listOf(
                "com.intellij.java",
                "com.intellij.gradle",
                "Git4Idea",
            ),
        )
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
        testImplementation(libs.junit)
    }

    implementation(kotlin("stdlib-jdk8"))

    implementation("javazoom:jlayer:1.0.1")

    implementation("com.github.umjammer:lamejb:0.2.1")
    implementation("net.java.dev.jna:jna:5.13.0")
    implementation("net.java.dev.jna:jna-platform:5.13.0")

    // MCP SDK (use its BOM to keep modules aligned)
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:0.14.0"))
    implementation("io.modelcontextprotocol:kotlin-sdk:0.7.2")

    // Ktor: manage versions via BOM (3.x)
    implementation(platform("io.ktor:ktor-bom:3.3.0"))
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-websockets")

    implementation("org.xerial:sqlite-jdbc:3.41.2.2")

    implementation("com.openai:openai-java:4.14.0")
    testRuntimeOnly("junit:junit:4.13.2")

    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testImplementation(kotlin("test"))
    testImplementation("org.mockito:mockito-core:4.11.0")

    // MockK for Kotlin mocking in tests
    testImplementation("io.mockk:mockk:1.13.5")
    // MockK agent to allow mocking final and static members when needed
    testImplementation("io.mockk:mockk-agent-jvm:1.13.5")
}

// ensure sqlite-jdbc is available in the plugin sandbox at runtime
tasks.register<Task>("copyRuntimeLibsToSandbox") {
    doLast {
        val sandboxLib = layout.buildDirectory.dir("idea-sandbox/plugins/${project.name}/lib").get().asFile
        sandboxLib.mkdirs()
        configurations.runtimeClasspath.get().forEach { file ->
            if (file.name.contains("sqlite-jdbc") || file.name.contains("sqlite")) {
                copy {
                    from(file)
                    into(sandboxLib)
                }
            }
        }
    }
}

tasks.register<Copy>("copyLicenses") {
    from("LICENSE.txt", "NOTICE.txt", "licenses")
    into(layout.buildDirectory.dir("distributions/licenses"))
}

tasks.named("buildPlugin") {
    dependsOn("shadowJar")
    dependsOn("copyLicenses")
}

tasks.named("runIde") {
    dependsOn("copyRuntimeLibsToSandbox")
}

spotless {
    kotlin {
        licenseHeaderFile(
            rootProject.file("config/license/HEADER")
        )
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
