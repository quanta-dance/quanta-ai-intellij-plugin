plugins {
    id("rpc")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
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
        bundledModule("intellij.platform.frontend")
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")

        composeUI()
    }

    compileOnly(project(":shared"))
    // kotlinx-serialization is provided by the IDE's frontend classpath — do not bundle
    compileOnly(libs.kotlin.serialization.core.jvm)
    compileOnly(libs.kotlin.serialization.json.jvm)
    implementation(libs.javazoom)

    testImplementation(kotlin("test"))
    testImplementation(kotlin("stdlib"))
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation(libs.byte.buddy)
    testImplementation(libs.byte.buddy.agent)
    testImplementation(project(":shared"))
    testRuntimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
    testRuntimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.10.2")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    withType<Test>().configureEach {
        jvmArgs("-Dkotlinx.coroutines.debug=off")
        systemProperty("kotlinx.coroutines.debug", "off")
    }
}
