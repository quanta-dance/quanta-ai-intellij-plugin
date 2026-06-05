// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.project

import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

object ProjectVersionUtil {
    private val buildFileNames =
        setOf(
            "build.gradle.kts",
            "build.gradle",
            "pom.xml",
            "build.sbt",
            "Cargo.toml",
            "go.mod",
            "go.work",
            "package.json",
        )

    fun getProjectBuildFiles(project: Project): List<String> {
        val projectRootPath = PathUtils.projectRootPath(project) ?: return emptyList()
        val projectBaseDir = LocalFileSystem.getInstance().findFileByPath(projectRootPath)
            ?: return emptyList()

        val foundFiles = mutableListOf<String>()
        collectProjectBuildFiles(projectRootPath, projectBaseDir, 0, 3, foundFiles)
        return foundFiles.distinct()
    }

    private fun collectProjectBuildFiles(
        projectRootPath: String,
        file: VirtualFile,
        currentDepth: Int,
        maxDepth: Int,
        result: MutableList<String>,
    ) {
        if (currentDepth > maxDepth) return
        if (!file.isDirectory) {
            if (file.name in buildFileNames) {
                result.add(file.path.removePrefix(projectRootPath).trimStart('/'))
            }
            return
        }

        file.children.forEach { child ->
            collectProjectBuildFiles(projectRootPath, child, currentDepth + 1, maxDepth, result)
        }
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
        val basePath = PathUtils.projectRootPath(project) ?: return ""
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return ""
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
            if (!javaVersion.isNullOrBlank()) append("Java $javaVersion")
            if (!kotlinVersion.isNullOrBlank()) append(if (isEmpty()) "Kotlin $kotlinVersion" else ", Kotlin $kotlinVersion")
            if (!kotlinJvmTarget.isNullOrBlank()) {
                append(
                    if (isEmpty()) "Kotlin JVM target $kotlinJvmTarget" else ", Kotlin JVM target $kotlinJvmTarget",
                )
            }
            if (!kotlinLangVersion.isNullOrBlank()) {
                append(
                    if (isEmpty()) "Kotlin language $kotlinLangVersion" else ", Kotlin language $kotlinLangVersion",
                )
            }
            if (!gradleVersion.isNullOrBlank()) append(if (isEmpty()) "Gradle $gradleVersion" else ", Gradle $gradleVersion")
            if (!mavenVersion.isNullOrBlank()) append(if (isEmpty()) "Maven $mavenVersion" else ", Maven $mavenVersion")
            if (!scalaVersion.isNullOrBlank()) append(if (isEmpty()) "Scala $scalaVersion" else ", Scala $scalaVersion")
            if (!goVersion.isNullOrBlank()) append(if (isEmpty()) "Go $goVersion" else ", Go $goVersion")
            if (!nodeVersion.isNullOrBlank()) append(if (isEmpty()) "Node $nodeVersion" else ", Node $nodeVersion")
            if (!tsVersion.isNullOrBlank()) append(if (isEmpty()) "TypeScript $tsVersion" else ", TypeScript $tsVersion")
            if (!rustVersion.isNullOrBlank()) append(if (isEmpty()) "Rust $rustVersion" else ", Rust $rustVersion")
        }
    }

    private fun getJavaVersion(project: Project): String? = null

    private fun getKotlinVersion(project: Project): String? = null

    private fun getKotlinTargetsFromBuild(project: Project): Pair<String?, String?> = null to null

    private fun getGradleVersion(project: Project): String? = null

    private fun getMavenVersion(project: Project): String? = null

    private fun getScalaVersion(project: Project): String? = null

    private fun getGoVersion(project: Project): String? = null

    private fun getNodeAndTsVersions(project: Project): Pair<String?, String?> = null to null

    private fun getRustVersion(project: Project): String? = null
}
