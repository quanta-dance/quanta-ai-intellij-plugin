// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import java.nio.charset.StandardCharsets

@JsonClassDescription(
    "Create or Update specified file with provided content. " +
        "Before modifying methods in the file you may need to check for this method references as they might need to be updated.",
)
class CreateOrUpdateFile : ToolInterface<String> {
    @JsonPropertyDescription("Relative to the project root path to the requested file.")
    var filePath: String? = null

    @JsonPropertyDescription(
        "New content for the file to be modified. This MUST be a complete replacement of file content. " +
            "Existing content of file will be replaced with this content.",
    )
    var content: String? = null

    @JsonPropertyDescription("If true, validates the updated file after write and reports compilation errors.")
    var validateAfterUpdate: Boolean = false

    companion object {
        private val logger = Logger.getInstance(CreateOrUpdateFile::class.java)
    }

    override fun execute(project: Project): String {
        var result: String = "File successfully updated"
        var lastModified: Long = 0
        val projectBase = project.basePath ?: return "Project base path not found."
        val resolved =
            try {
                PathUtils.resolveWithinProject(projectBase, filePath)
            } catch (e: IllegalArgumentException) {
                project.service<ToolWindowService>()
                    .addToolingMessage("Modify File - rejected", e.message ?: "Invalid path")
                QDLog.warn(logger, { "Invalid path for CreateOrUpdateFile: $filePath" }, e)
                return e.message ?: "Invalid path"
            }
        val relToBase = PathUtils.relativizeToProject(projectBase, resolved)

        var updatedVirtualFile: VirtualFile? = null

        // Perform the file content update within a write action and commit/save document
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    val ioFile = resolved.toFile()
                    ioFile.parentFile?.mkdirs()
                    val parentVf =
                        try {
                            VfsUtil.createDirectories(ioFile.parentFile.absolutePath)
                        } catch (e: Throwable) {
                            QDLog.debug(logger) { "VFS createDirectories failed: ${e.message}" }
                            val found =
                                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile.parentFile)
                                    ?: throw IllegalStateException("Unable to create directories for: ${ioFile.parentFile}")
                            found
                        }
                    val virtualFile =
                        parentVf.findChild(ioFile.name)
                            ?: parentVf.createChildData(this, ioFile.name)
                    updatedVirtualFile = virtualFile

                    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                    if (document != null) {
                        try {
                            document.setText(content ?: "")
                            PsiDocumentManager.getInstance(project).commitDocument(document)
                            FileDocumentManager.getInstance().saveDocument(document)
                        } catch (e: Throwable) {
                            QDLog.debug(logger) { "Failed to set/commit/save document: ${e.message}" }
                        }
                    } else {
                        try {
                            virtualFile.setBinaryContent((content ?: "").toByteArray(StandardCharsets.UTF_8))
                        } catch (e: Throwable) {
                            QDLog.debug(logger) { "Failed to set binary content: ${e.message}" }
                        }
                    }
                    try {
                        FileEditorManager.getInstance(project)
                            .openTextEditor(OpenFileDescriptor(project, virtualFile), true)
                    } catch (e: Throwable) {
                        QDLog.debug(logger) { "Failed to open editor for $relToBase: ${e.message}" }
                    }
                    project.service<ToolWindowService>().addToolingMessage("File updated", relToBase)
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    lastModified = psiFile?.modificationStamp ?: 0
                } catch (e: Throwable) {
                    QDLog.warn(logger, { "Failed to update file $relToBase" }, e)
                    project.service<ToolWindowService>()
                        .addToolingMessage("Modify File - failed", e.message ?: "Write action failed")
                    throw e
                }
            }
        }

        // Ensure documents are committed and VFS refreshed so validation sees the latest content
        try {
            try {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            } catch (e: Throwable) {
                QDLog.debug(logger) { "commitAllDocuments failed: ${e.message}" }
            }
            FileDocumentManager.getInstance().saveAllDocuments()
            val ioFile = resolved.toFile()
            val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            if (vFile != null) {
                try {
                    VfsUtil.markDirtyAndRefresh(true, true, true, vFile)
                    updatedVirtualFile = vFile
                } catch (e: Throwable) {
                    QDLog.debug(logger) { "VFS markDirtyAndRefresh failed: ${e.message}" }
                }
                try {
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                } catch (e: Throwable) {
                    QDLog.debug(logger) { "commitAllDocuments after refresh failed: ${e.message}" }
                }
            }
        } catch (e: Throwable) {
            QDLog.debug(logger) { "Post-write sync failed: ${e.message}" }
        }

        if (validateAfterUpdate) {
            try {
                val validator = ValidateClassFileTool()
                validator.filePath = relToBase
                val errors =
                    ApplicationManager.getApplication().runReadAction<List<String>> {
                        validator.findErrors(project)
                    }
                val summary =
                    if (errors.size == 1 && errors.first().equals("No compilation errors found.", ignoreCase = true)) {
                        "No compilation errors found."
                    } else if (errors.isEmpty()) {
                        "Validation completed, no errors reported."
                    } else {
                        errors.joinToString("\n").let { if (it.length > 2000) it.take(2000) + "\n..." else it }
                    }
                result += "\nValidation: " + summary.lines().first()
            } catch (e: Throwable) {
                result += "\nValidation: skipped (${e.message})"
                QDLog.warn(logger, { "Validation unavailable for $relToBase" }, e)
            }
        }

        QDLog.info(logger) { "Update file $relToBase: $result, file version: $lastModified" }
        return result
    }
}
