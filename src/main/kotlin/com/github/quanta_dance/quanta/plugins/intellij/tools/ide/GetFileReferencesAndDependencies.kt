// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.project.CodeReferenceSelector.getAllReferencesAndDefinitions
import com.github.quanta_dance.quanta.plugins.intellij.project.DependencyResolver.resolveImportsToDependencies
import com.github.quanta_dance.quanta.plugins.intellij.project.ProjectVersionUtil.getProjectBuildFiles
import com.github.quanta_dance.quanta.plugins.intellij.project.ProjectVersionUtil.getProjectCompileVersion
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager

@JsonClassDescription("Get file dependencies which are imports including library versions, methods and function referenced to other files")
class GetFileReferencesAndDependencies : ToolInterface<List<String>> {
    @field:JsonPropertyDescription("Relative to the project root path to the requested file.")
    var filePath: String? = null

    override fun execute(project: Project): List<String> {
        return ApplicationManager.getApplication().runReadAction<List<String>> {
            project.service<ToolWindowService>().addToolingMessage(
                "Get file references",
                "$filePath",
            )
            val virtualFile = project.baseDir?.findFileByRelativePath(filePath!!)
            if (virtualFile != null) {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile != null) {
                    val dependencies =
                        try {
                            resolveImportsToDependencies(project, psiFile)
                        } catch (e: Throwable) {
                            emptySet<String>()
                        }
                    val allRefs =
                        try {
                            getAllReferencesAndDefinitions(psiFile, project)
                        } catch (e: Throwable) {
                            emptyList<String>()
                        }
                    val sdkVersion =
                        try {
                            getProjectCompileVersion(project)
                        } catch (e: Throwable) {
                            ""
                        }
                    val buildFiles =
                        try {
                            getProjectBuildFiles(project)
                        } catch (e: Throwable) {
                            emptyList<String>()
                        }

                    listOf(
                        "Available build files: $buildFiles\n$sdkVersion\nLibraries versions used in the import for reviewing file:\n${
                            dependencies.joinToString(
                                "\n",
                            )
                        }",
                        "References:\n${allRefs.joinToString(separator = "\n")}",
                    )
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }
    }
}
