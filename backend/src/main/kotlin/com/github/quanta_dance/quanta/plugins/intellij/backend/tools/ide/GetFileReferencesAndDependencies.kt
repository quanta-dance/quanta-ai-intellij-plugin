// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.project.CodeReferenceSelector.getAllReferencesAndDefinitions
import com.github.quanta_dance.quanta.plugins.intellij.backend.project.DependencyResolver.resolveImportsToDependencies
import com.github.quanta_dance.quanta.plugins.intellij.backend.project.ProjectVersionUtil.getProjectBuildFiles
import com.github.quanta_dance.quanta.plugins.intellij.backend.project.ProjectVersionUtil.getProjectCompileVersion
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager

/**
 * Backend analysis tool that combines dependency resolution with PSI reference discovery for one file.
 *
 * It is useful when an agent needs to understand both external library usage and internal code
 * connections before making a targeted change.
 */
@JsonClassDescription(
    "Get file dependencies (imports resolved to libraries with versions) and PSI-based references/definitions for a given file.",
)
class GetFileReferencesAndDependencies : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("Relative to the project root path to the requested file.")
    var filePath: String? = null

    private val log = Logger.getInstance(GetFileReferencesAndDependencies::class.java)

    override fun execute(project: Project): Map<String, Any> {
        val rel = filePath?.trim().orEmpty()
        if (rel.isEmpty()) return mapOf("status" to "error", "message" to "filePath is required")
        val base =
            PathUtils.projectRootPath(project) ?: return mapOf(
                "status" to "error",
                "message" to "Project base path not found.",
            )
        val vFile =
            try {
                PathUtils.resolveVirtualFileWithinProject(project, rel)
            } catch (e: IllegalArgumentException) {
                return mapOf("status" to "error", "message" to (e.message ?: "Invalid path"))
            }
                ?: return mapOf("status" to "error", "message" to "File not found: $rel")

        return ApplicationManager.getApplication().runReadAction<Map<String, Any>> {
            try {
                val psiFile =
                    PsiManager.getInstance(project).findFile(vFile)
                        ?: return@runReadAction mapOf("status" to "error", "message" to "PSI file not found: $rel")

                val dependencies: Set<String> =
                    try {
                        resolveImportsToDependencies(project, psiFile)
                    } catch (_: Throwable) {
                        emptySet()
                    }
                val allRefs: List<String> =
                    try {
                        getAllReferencesAndDefinitions(psiFile, project)
                    } catch (_: Throwable) {
                        emptyList()
                    }
                val sdkVersion: String =
                    try {
                        getProjectCompileVersion(project)
                    } catch (_: Throwable) {
                        ""
                    }
                val buildFiles: List<String> =
                    try {
                        getProjectBuildFiles(project)
                    } catch (_: Throwable) {
                        emptyList()
                    }

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
