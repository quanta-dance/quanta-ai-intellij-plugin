// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object ProjectVersionUtil {
    fun getProjectBuildFiles(project: Project): List<String> {
        val projectBaseDir =
            project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return emptyList()

        return projectBaseDir.children.filter { file ->
            file.name in listOf("build.gradle.kts", "build.gradle", "pom.xml", "build.sbt", "Cargo.toml", "go.mod", "package.json")
        }.map { it.name }
    }

    fun buildProjectFileTree(
        file: VirtualFile,
        baseDir: VirtualFile,
        fileIndex: ProjectFileIndex,
        indent: String = "",
        builder: StringBuilder = StringBuilder(),
    ): StringBuilder {
        if (!fileIndex.isInContent(file)) return builder

        if (file == baseDir) {
            builder.append("/\n")
        } else {
            builder.append(indent)
            if (file.isDirectory) {
                builder.append(file.name).append("/\n")
            } else {
                builder.append(file.name).append("\n")
            }
        }
        if (file.isDirectory) {
            val children = file.children.sortedBy { it.name }
            for (child in children) {
                buildProjectFileTree(child, baseDir, fileIndex, indent + "  ", builder)
            }
        }
        return builder
    }

    fun getProjectTreeAsString(project: Project): String {
        val baseDir = project.baseDir
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        return ApplicationManager.getApplication().runReadAction<String> {
            buildProjectFileTree(baseDir, baseDir, fileIndex).toString()
        }
    }

    fun getProjectCompileVersion(project: Project): String {
        val javaVersion = getJavaVersion(project)
        val kotlinVersion = getKotlinVersion(project)
        val (kotlinJvmTarget, kotlinLangVersion) = getKotlinTargetsFromBuild(project)
        val gradleVersion = getGradleVersion(project)
        val mavenVersion = getMavenVersion(project)
        val scalaVersion = getScalaVersion(project)
        val goVersion = getGoVersion(project)
        val (nodeVersion, tsVersion) = getNodeAndTsVersions(project)
        val rustVersion = getRustVersion(project)

        return buildString {
            if (!javaVersion.isNullOrEmpty()) append("Java: $javaVersion\n")
            if (!kotlinVersion.isNullOrEmpty()) append("Kotlin: $kotlinVersion\n")
            if (!kotlinJvmTarget.isNullOrEmpty()) append("Kotlin JVM target: $kotlinJvmTarget\n")
            if (!kotlinLangVersion.isNullOrEmpty()) append("Kotlin language: $kotlinLangVersion\n")
            if (!gradleVersion.isNullOrEmpty()) append("Gradle: $gradleVersion\n")
            if (!mavenVersion.isNullOrEmpty()) append("Maven: $mavenVersion\n")
            if (!scalaVersion.isNullOrEmpty()) append("Scala: $scalaVersion\n")
            if (!goVersion.isNullOrEmpty()) append("Go: $goVersion\n")
            if (!nodeVersion.isNullOrEmpty()) append("Node: $nodeVersion\n")
            if (!tsVersion.isNullOrEmpty()) append("TypeScript: $tsVersion\n")
            if (!rustVersion.isNullOrEmpty()) append("Rust: $rustVersion")
        }.trim()
    }

    private fun getJavaVersion(project: Project): String? {
        val sdk = ProjectRootManager.getInstance(project).projectSdk
        return sdk?.versionString
    }

    private fun sanitizeKotlinVersion(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        var v = raw
        if (v.endsWith(".jar")) v = v.removeSuffix(".jar")
        if (v.endsWith("-sources")) v = v.removeSuffix("-sources")
        return v
    }

    private fun getKotlinVersion(project: Project): String? {
        // Try from classpath libs first (kotlin-stdlib-<ver>.jar)
        val libs = OrderEnumerator.orderEntries(project).librariesOnly().classesRoots
        val fromJar =
            libs.firstOrNull { vf ->
                val p = vf.path
                p.contains("kotlin-stdlib-") || p.contains("kotlin-reflect-") || p.contains("kotlin-stdlib-jdk")
            }?.path?.let { path ->
                // Extract version after kotlin-stdlib- or kotlin-reflect-
                val re = Regex("kotlin-(?:stdlib(?:-jdk[0-9]+)?|reflect)-([0-9][0-9a-zA-Z+_.-]*)")
                re.find(path)?.groupValues?.getOrNull(1)
            }
        val cleanedJar = sanitizeKotlinVersion(fromJar)
        if (!cleanedJar.isNullOrBlank()) return cleanedJar

        // Try to parse Kotlin Gradle plugin version from build files
        val base = project.basePath ?: return null
        val gradleKts = LocalFileSystem.getInstance().findFileByPath("$base/build.gradle.kts")
        val gradleGroovy = LocalFileSystem.getInstance().findFileByPath("$base/build.gradle")
        val versionFromBuild =
            sequenceOf(gradleKts, gradleGroovy).mapNotNull { vf -> vf?.let { readText(it) } }
                .mapNotNull { text ->
                    // plugins { kotlin("jvm") version "1.9.24" } or id("org.jetbrains.kotlin.jvm") version "1.9.24"
                    val r1 = Regex("""kotlin\("[^\"]+"\)\s+version\s+"([^\"]+)"""")
                    val r2 = Regex("""id\("org\.jetbrains\.kotlin\.[^"]+"\)\s+version\s+"([^"]+)"""")
                    r1.find(text)?.groupValues?.getOrNull(1) ?: r2.find(text)?.groupValues?.getOrNull(1)
                }
                .firstOrNull()
        return sanitizeKotlinVersion(versionFromBuild)
    }

    private fun getKotlinTargetsFromBuild(project: Project): Pair<String?, String?> {
        val base = project.basePath ?: return null to null
        val gradleKts = LocalFileSystem.getInstance().findFileByPath("$base/build.gradle.kts")
        val gradleGroovy = LocalFileSystem.getInstance().findFileByPath("$base/build.gradle")
        val text = readText(gradleKts) ?: readText(gradleGroovy) ?: return null to null

        // jvmToolchain(17)
        val jvmToolchain = Regex("""jvmToolchain\((\d+)\)""").find(text)?.groupValues?.getOrNull(1)

        // kotlinOptions { jvmTarget = "17" } or jvmTarget = 17
        val jvmTarget =
            Regex("""kotlinOptions\s*\{[^}]*jvmTarget\s*=\s*"?([A-Za-z0-9_.]+)"?""")
                .find(text)?.groupValues?.getOrNull(1)
                ?: Regex("""compilerOptions\s*\{[^}]*jvmTarget\.set\(JvmTarget\.JVM_(\d+)\)""")
                    .find(text)?.groupValues?.getOrNull(1)

        // languageVersion = "2.0" or compilerOptions { languageVersion.set(KotlinVersion.KOTLIN_2_0) }
        var lang = Regex("""languageVersion\s*=\s*"([0-9][0-9_.]*)"""").find(text)?.groupValues?.getOrNull(1)
        if (lang == null) {
            val m = Regex("""languageVersion\.set\((?:KotlinVersion\.)?KOTLIN_([0-9]+_[0-9]+)\)""").find(text)
            lang = m?.groupValues?.getOrNull(1)?.replace('_', '.')
        }

        val target = jvmTarget ?: jvmToolchain
        return target to lang
    }

    private fun getGradleVersion(project: Project): String? {
        val base = project.basePath ?: return null
        val wrapper =
            LocalFileSystem.getInstance()
                .findFileByPath("$base/gradle/wrapper/gradle-wrapper.properties")
                ?: return null
        val text = readText(wrapper) ?: return null
        // distributionUrl=https://services.gradle.org/distributions/gradle-8.9-bin.zip
        val m = Regex("""distributionUrl=.*gradle-([0-9.]+)-""").find(text)
        return m?.groupValues?.getOrNull(1)
    }

    private fun getMavenVersion(project: Project): String? {
        val base = project.basePath ?: return null
        val wrapper =
            LocalFileSystem.getInstance()
                .findFileByPath("$base/.mvn/wrapper/maven-wrapper.properties")
                ?: return null
        val text = readText(wrapper) ?: return null
        // distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.8/apache-maven-3.9.8-bin.zip
        val m = Regex("""distributionUrl=.*apache-maven-([0-9.]+)-""").find(text)
        return m?.groupValues?.getOrNull(1)
    }

    private fun getScalaVersion(project: Project): String? {
        val modules = OrderEnumerator.orderEntries(project).librariesOnly().classesRoots
        return modules.firstOrNull { it.path.contains("scala-library-") }
            ?.path
            ?.substringAfterLast("scala-library-")
            ?.removeSuffix(".jar")
    }

    private fun getGoVersion(project: Project): String? {
        // Heuristic: look for a module/jar path containing goX.Y
        val modules = OrderEnumerator.orderEntries(project).classesRoots
        return modules.firstOrNull { it.path.contains("/go") || it.path.contains("\\go") }
            ?.path
            ?.substringAfterLast("go")
            ?.takeWhile { it.isDigit() || it == '.' }
    }

    private fun getNodeAndTsVersions(project: Project): Pair<String?, String?> {
        val base = project.basePath ?: return null to null
        val pkgJson = LocalFileSystem.getInstance().findFileByPath("$base/package.json")
        var node: String? = null
        var ts: String? = null
        readText(pkgJson)?.let { text ->
            // engines: { "node": ">=18" }
            node =
                Regex(""""engines"\s*:\s*\{[^}]*"node"\s*:\s*"([^"]+)"""")
                    .find(text)?.groupValues?.getOrNull(1)
            // dependencies/devDependencies: { "typescript": "^5.4.0" }
            ts = Regex(""""typescript"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.getOrNull(1)
        }
        // .nvmrc overrides engines
        val nvmrc = LocalFileSystem.getInstance().findFileByPath("$base/.nvmrc")
        readText(nvmrc)?.trim()?.let { if (it.isNotEmpty()) node = it }
        // .tool-versions (asdf): lines like "node 20.10.0"
        val toolVersions = LocalFileSystem.getInstance().findFileByPath("$base/.tool-versions")
        readText(toolVersions)?.lines()?.forEach { line ->
            val m = Regex("""^node\s+([A-Za-z0-9_.-]+)""").find(line.trim())
            if (m != null) node = m.groupValues[1]
        }
        return node to ts
    }

    private fun getRustVersion(project: Project): String? {
        val base = project.basePath ?: return null
        // rust-toolchain (plain) or rust-toolchain.toml
        val plain = LocalFileSystem.getInstance().findFileByPath("$base/rust-toolchain")
        val toml = LocalFileSystem.getInstance().findFileByPath("$base/rust-toolchain.toml")
        readText(plain)?.trim()?.let { if (it.isNotEmpty()) return it }
        val t = readText(toml) ?: return null
        // channel = "1.75.0" or under [toolchain] channel = "stable"
        val m = Regex("""channel\s*=\s*"([^"]+)"""").find(t)
        return m?.groupValues?.getOrNull(1)
    }

    private fun readText(vf: VirtualFile?): String? {
        if (vf == null) return null
        return try {
            vf.inputStream.use { ins ->
                BufferedReader(InputStreamReader(ins, StandardCharsets.UTF_8)).readText()
            }
        } catch (_: Throwable) {
            null
        }
    }
}
