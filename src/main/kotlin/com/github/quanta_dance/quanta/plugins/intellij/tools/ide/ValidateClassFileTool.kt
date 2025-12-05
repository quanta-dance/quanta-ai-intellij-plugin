// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import java.io.File
import java.nio.file.Paths

@JsonClassDescription("Validate a class file and return any compilation errors.")
class ValidateClassFileTool : ToolInterface<List<String>> {
    @field:JsonPropertyDescription("Relative to project root path to the file to validate.")
    var filePath: String? = null

    companion object {
        private val logger = Logger.getInstance(ValidateClassFileTool::class.java)
    }

    fun getCompilationErrors(
        project: Project,
        psiFile: PsiFile,
    ): List<String> {
        val errors = mutableListOf<String>()
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return errors

        // Collect syntax/parse errors using public PSI API
        psiFile.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitErrorElement(element: PsiErrorElement) {
                    val line = document.getLineNumber(element.textOffset) + 1
                    errors.add("Error: ${element.errorDescription} at line $line")
                }
            },
        )

        return errors
    }

    fun findErrors(project: Project): List<String> {
        val errors = mutableListOf<String>()
        if (filePath.isNullOrBlank()) return listOf("Class file path is not specified.")
        val basePath = project.basePath
        if (basePath.isNullOrBlank()) return listOf("Project base path not found.")

        // Refresh and find VFS file by absolute path to avoid stale baseDir-based lookups
        val absPath = Paths.get(basePath, filePath).toString()
        val vFile =
            try {
                val ioFile = File(absPath)
                VfsUtil.markDirtyAndRefresh(true, true, true, ioFile)
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            } catch (t: Throwable) {
                null
            }
        if (vFile == null) {
            errors.add("Class file not found.")
            return errors
        }

        // Commit docs so PSI is up to date
        try {
            FileDocumentManager.getInstance().saveAllDocuments()
        } catch (_: Throwable) {
        }
        try {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        } catch (_: Throwable) {
        }

        val psiFile = PsiManager.getInstance(project).findFile(vFile)
        if (psiFile == null) {
            errors.add("PSI file not found.")
            return errors
        }

        errors.addAll(getCompilationErrors(project, psiFile))

        if (errors.isEmpty()) {
            QDLog.debug(logger) { "No compilation errors found for $filePath" }
            return listOf("No compilation errors found.")
        }
        return errors
    }

    override fun execute(project: Project): List<String> {
        if (filePath.isNullOrEmpty()) return listOf("Class file path is not specified.")
        return ApplicationManager.getApplication().runReadAction<List<String>> {
            val errors = findErrors(project)
            project.service<ToolWindowService>().addToolingMessage(
                "Validate compilation errors " + filePath.orEmpty(),
                errors.joinToString("\n"),
            )
            errors
        }
    }
}
