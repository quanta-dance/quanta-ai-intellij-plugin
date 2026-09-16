import org.gradle.api.file.RelativePath
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
    compileOnly("io.modelcontextprotocol.sdk:mcp-core:2.0.1")
    compileOnly("io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.1")
    // Keep MCP transitive runtime dependencies on verified patch releases while retaining
    // the SDK's compatible major versions.
    compileOnly("io.projectreactor:reactor-core:3.7.19")
    compileOnly("com.networknt:json-schema-validator:2.0.7")
    // Satisfy optional regex/context implementations packaged inside the required MCP runtime.
    compileOnly("io.micrometer:context-propagation:1.2.1")
    // Reactor's optional metrics API links against the compatible Micrometer core API.
    compileOnly("io.micrometer:micrometer-core:1.12.10")
    compileOnly("org.jruby.joni:joni:2.2.6")
    // NetworkNT's optional GraalJS regex backend compiles against this API artifact.
    compileOnly("org.graalvm.sdk:graal-sdk:21.3.10")

    backendRuntime(libs.openai)
    backendRuntime("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    backendRuntime("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    backendRuntime("io.modelcontextprotocol.sdk:mcp-core:2.0.1")
    backendRuntime("io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.1")
    backendRuntime("io.projectreactor:reactor-core:3.7.19")
    backendRuntime("com.networknt:json-schema-validator:2.0.7")
    backendRuntime("io.micrometer:context-propagation:1.2.1")
    backendRuntime("io.micrometer:micrometer-core:1.12.10")
    backendRuntime("org.jruby.joni:joni:2.2.6")
    backendRuntime("org.graalvm.sdk:graal-sdk:21.3.10")

    testImplementation(libs.openai)
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    testImplementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")
    testImplementation("io.modelcontextprotocol.sdk:mcp-core:2.0.1")
    testImplementation("io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.1")
    testImplementation("io.projectreactor:reactor-core:3.7.19")
    testImplementation("com.networknt:json-schema-validator:2.0.7")
    testImplementation("io.micrometer:context-propagation:1.2.1")
    testImplementation("io.micrometer:micrometer-core:1.12.10")
    testImplementation("org.jruby.joni:joni:2.2.6")
    testImplementation("org.graalvm.sdk:graal-sdk:21.3.10")
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
                    // Exclude the OpenAI OkHttp transport and its dependencies: the backend uses
                    // OpenAIJdkHttpClient, and the IntelliJ runtime does not provide OkHttp.
                    .filter { !it.file.name.startsWith("openai-java-client-okhttp") }
                    .filter { !it.file.name.startsWith("okhttp") }
                    .filter { !it.file.name.startsWith("okio") }
                    // jackson-core is provided by the IDE platform; bundling it adds shaded
                    // FastDoubleParse classes that reference VarHandle.set unavailable on older JVMs
                    .filter { !it.file.name.startsWith("jackson-core") }
                    .map {
                        zipTree(it.file).matching {
                            // Defense in depth: keep the optional OpenAI OkHttp transport out even
                            // if future OpenAI artifacts package it alongside core classes.
                            exclude("com/openai/core/http/okhttp/**")
                            exclude("okhttp3/**")
                            exclude("okio/**")
                            // Fat-JAR assembly keeps only this plugin's manifest, so it cannot retain
                            // dependency Multi-Release attributes. Exclude dependency version overlays;
                            // the Java 11 Reactor call-site implementation is restored below.
                            exclude("META-INF/versions/**")
                            exclude("reactor/core/publisher/CallSiteSupplierFactory*")
                            // Reactor's metrics and BlockHound bridges are optional integrations that
                            // are not used by the MCP client.
                            exclude("META-INF/services/reactor.blockhound.integration.BlockHoundIntegration")
                            // Reactor uses Micrometer's core instrument types, but the optional binders
                            // target unrelated application frameworks (Jetty, Hibernate, servlet APIs, etc.).
                            // Reactor's scheduler metrics bridge additionally requires this class; restore it below.
                            exclude("io/micrometer/core/instrument/binder/**")
                            // Micrometer's OkHttp sender is an optional HTTP registry transport. The backend
                            // uses the JDK HTTP client and does not package OkHttp or Okio.
                            exclude("io/micrometer/core/ipc/http/OkHttpSender*")
                            exclude("io/micrometer/core/instrument/dropwizard/**")
                            // Micrometer AOP integrations require AspectJ, which is not used by the MCP client.
                            exclude("io/micrometer/core/aop/**")
                            exclude("io/micrometer/common/annotation/**")
                            exclude("io/micrometer/observation/aop/**")
                            // Android TLS stubs — dead code on JVM.
                            exclude("android/**")
                            // kotlin-logging-jvm's logback integration references ch.qos.logback,
                            // which is not bundled in the IDE.
                            exclude("io/github/oshai/kotlinlogging/logback/**")
                            // The Java MCP SDK includes servlet server transports. This plugin is an
                            // MCP client and does not bundle the Jakarta Servlet API.
                            exclude("io/modelcontextprotocol/server/transport/HttpServlet**")
                            // Reactor's BlockHound integration is optional and needs an absent library.
                            exclude("reactor/core/scheduler/ReactorBlockHoundIntegration.class")
                        }
                    }
            },
        )
        // Reactor's Java 8 call-site implementation references unavailable sun.misc APIs. Restore its
        // Java 11 implementation at the normal class path because a flattened JAR is not multi-release.
        from(
            provider {
                backendRuntime
                    .resolvedConfiguration
                    .resolvedArtifacts
                    .filter { it.moduleVersion.id.group == "io.projectreactor" && it.name == "reactor-core" }
                    .map { zipTree(it.file).matching { include("META-INF/versions/11/reactor/core/publisher/CallSiteSupplierFactory.class") } }
            },
        ) {
            eachFile {
                relativePath = RelativePath(true, "reactor", "core", "publisher", "CallSiteSupplierFactory.class")
            }
        }
        // Reactor's SchedulerMetricDecorator directly links this single Micrometer JVM binder.
        // Restore it and its lightweight MeterBinder interface after removing unrelated binders above.
        from(
            provider {
                backendRuntime
                    .resolvedConfiguration
                    .resolvedArtifacts
                    .filter { it.moduleVersion.id.group == "io.micrometer" && it.name == "micrometer-core" }
                    .map {
                        zipTree(it.file).matching {
                            include("io/micrometer/core/instrument/binder/MeterBinder.class")
                            include("io/micrometer/core/instrument/binder/jvm/ExecutorServiceMetrics*")
                        }
                    }
            },
        )
    }
}