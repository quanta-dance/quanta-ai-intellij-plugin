// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.project.CodeReferenceSelector.getAllReferencesAndDefinitions
import com.github.quanta_dance.quanta.plugins.intellij.project.DependencyResolver.resolveImportsToDependencies
import com.github.quanta_dance.quanta.plugins.intellij.project.ProjectVersionUtil.getProjectBuildFiles
import com.github.quanta_dance.quanta.plugins.intellij.project.ProjectVersionUtil.getProjectCompileVersion
import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import java.io.File

@JsonClassDescription("Get file dependencies (imports resolved to libraries with versions) and PSI-based references/definitions for a given file.")
class GetFileReferencesAndDependencies : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("Relative to the project root path to the requested file.")
    var filePath: String? = null

    private val log = Logger.getInstance(GetFileReferencesAndDependencies::class.java)

    override fun execute(project: Project): Map<String, Any> {
        val rel = filePath?.trim().orEmpty()
        if (rel.isEmpty()) return mapOf("status" to "error", "message" to "filePath is required")
        val base = project.basePath ?: return mapOf("status" to "error", "message" to "Project base path not found.")
        val resolved: File = try { PathUtils.resolveWithinProject(base, rel).toFile() } catch (e: IllegalArgumentException) {
            project.service<ToolWindowService>().addToolingMessage("Get file references - invalid path", e.message ?: "Invalid path")
            return mapOf("status" to "error", "message" to (e.message ?: "Invalid path"))
        }
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(resolved)
            ?: run {
                VfsUtil.markDirtyAndRefresh(true, true, true, File(base))
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(resolved)
            }
            ?: return mapOf("status" to "error", "message" to "File not found: $rel")

        return ApplicationManager.getApplication().runReadAction<Map<String, Any>> {
            try {
                val psiFile = PsiManager.getInstance(project).findFile(vFile)
                    ?: return@runReadAction mapOf("status" to "error", "message" to "PSI file not found: $rel")

                val dependencies: Set<String> =
                    try { resolveImportsToDependencies(project, psiFile) } catch (_: Throwable) { emptySet() }
                val allRefs: List<String> =
                    try { getAllReferencesAndDefinitions(psiFile, project) } catch (_: Throwable) { emptyList() }
                val sdkVersion: String =
                    try { getProjectCompileVersion(project) } catch (_: Throwable) { "" }
                val buildFiles: List<String> =
                    try { getProjectBuildFiles(project) } catch (_: Throwable) { emptyList() }

                project.service<ToolWindowService>().addToolingMessage("Get file references", rel)

                mapOf(
                    "status" to "ok",
                    "file" to rel,
                    "buildFiles" to buildFiles,
                    "sdkVersion" to sdkVersion,
                    "dependencies" to dependencies.sorted(),
                    "references" to allRefs,
                )
            } catch (t: Throwable) {
                QDLog.warn(log) { "GetFileReferencesAndDependencies failed: ${t.message}" }
                mapOf("status" to "error", "message" to (t.message ?: t.javaClass.simpleName))
            }
        }
    }
}
