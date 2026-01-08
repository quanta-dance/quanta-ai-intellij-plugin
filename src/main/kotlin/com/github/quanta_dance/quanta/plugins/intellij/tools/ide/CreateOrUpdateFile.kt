// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
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
import com.intellij.psi.codeStyle.CodeStyleManager
import java.nio.charset.StandardCharsets

@JsonClassDescription(
    "Create or Update specified file. Supports full replacement via 'content' or partial line-range updates via 'patches'. " +
        "Before modifying methods in the file you may need to check for method references as they might need updates.",
)
class CreateOrUpdateFile : ToolInterface<String> {
    data class Patch(
        @field:JsonPropertyDescription("1-based start line (inclusive)")
        var fromLine: Int = 1,
        @field:JsonPropertyDescription("1-based end line (inclusive)")
        var toLine: Int = 1,
        @field:JsonPropertyDescription("Replacement content for the specified line range")
        var newContent: String = "",
        @field:JsonPropertyDescription("Optional expected current text for the specified line range")
        var expectedText: String? = null,
    )

    @field:JsonPropertyDescription("Relative to the project root path to the requested file.")
    var filePath: String? = null

    @field:JsonPropertyDescription(
        "New content for the file to be modified. If provided and 'patches' is empty, " +
            "this fully replaces file content.",
    )
    var content: String? = null

    @field:JsonPropertyDescription("If true, validates the updated file after write and reports compilation errors.")
    var validateAfterUpdate: Boolean = true

    @field:JsonPropertyDescription(
        "Optional list of line-range patches to apply (1-based inclusive lines). If non-empty, " +
            "patches are applied instead of full replace.",
    )
    var patches: List<Patch>? = null

    @field:JsonPropertyDescription(
        "If true, force synchronous save/commit/refresh " +
            "to surface PSI errors immediately (no Gradle run). Default: true",
    )
    var validateBuildAfterUpdate: Boolean = true

    // Pass-through guards for patch mode
    @field:JsonPropertyDescription("If true (default), aborts and applies nothing when any patch guard fails.")
    var stopOnMismatch: Boolean = true

    @field:JsonPropertyDescription(
        "Optional expected file version before patching (PSI/Document/VFS). " +
            "If differs, no changes are applied.",
    )
    var expectedFileVersion: Long? = null

    // PSI post-processing
    @field:JsonPropertyDescription("If true, reformat the PSI file after update.")
    var reformatAfterUpdate: Boolean = false

    @field:JsonPropertyDescription("If true, optimize imports after update.")
    var optimizeImportsAfterUpdate: Boolean = false

    companion object {
        private val logger = Logger.getInstance(CreateOrUpdateFile::class.java)
    }

    private fun flushPsiAndVfs(
        project: Project,
        target: VirtualFile?,
    ) {
        try {
            var attempts = 0
            while (attempts < 3) {
                try {
                    FileDocumentManager.getInstance().saveAllDocuments()
                } catch (_: Throwable) {
                }
                try {
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                } catch (_: Throwable) {
                }
                if (!PsiDocumentManager.getInstance(project).hasUncommitedDocuments()) break
                attempts++
            }
            if (target != null) {
                try {
                    VfsUtil.markDirtyAndRefresh(true, false, false, target)
                } catch (_: Throwable) {
                }
            }
            try {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
    }

    override fun execute(project: Project): String {
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

        val patchList = patches?.toList().orEmpty()
        if (patchList.isNotEmpty()) {
            val pf =
                PatchFile().apply {
                    filePath = relToBase
                    patches = patchList.map { p -> PatchFile.Patch(p.fromLine, p.toLine, p.newContent, p.expectedText) }
                    validateAfterUpdate = this@CreateOrUpdateFile.validateAfterUpdate
                    stopOnMismatch = this@CreateOrUpdateFile.stopOnMismatch
                    expectedFileVersion = this@CreateOrUpdateFile.expectedFileVersion
                    reformatAfterUpdate = this@CreateOrUpdateFile.reformatAfterUpdate
                    optimizeImportsAfterUpdate = this@CreateOrUpdateFile.optimizeImportsAfterUpdate
                }
            val result = pf.execute(project)
            if (validateBuildAfterUpdate) {
                val ioFile = resolved.toFile()
                val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                flushPsiAndVfs(project, vFile)
            }
            return if (validateAfterUpdate) result + "\n" + runPsiValidation(project, relToBase) else result
        }

        var result: String = "File successfully updated"
        var lastModified: Long = 0
        var updatedVirtualFile: VirtualFile? = null

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
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile.parentFile)
                                ?: throw IllegalStateException("Unable to create directories for: ${ioFile.parentFile}")
                        }
                    val virtualFile = parentVf.findChild(ioFile.name) ?: parentVf.createChildData(this, ioFile.name)
                    updatedVirtualFile = virtualFile

                    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                    if (document != null) {
                        try {
                            document.setText(content ?: "")
                            PsiDocumentManager.getInstance(project)
                                .commitDocument(document)
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

                    // PSI post-processing
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    if (psiFile != null) {
                        try {
                            if (reformatAfterUpdate) CodeStyleManager.getInstance(project).reformat(psiFile)
                        } catch (_: Throwable) {
                        }
                        try {
                            if (optimizeImportsAfterUpdate) OptimizeImportsProcessor(project, psiFile).run()
                        } catch (_: Throwable) {
                        }
                    }

                    try {
                        FileEditorManager.getInstance(project)
                            .openTextEditor(OpenFileDescriptor(project, virtualFile), true)
                    } catch (_: Throwable) {
                    }
                    project.service<ToolWindowService>().addToolingMessage("File updated", relToBase)
                    lastModified = psiFile?.modificationStamp ?: 0
                } catch (e: Throwable) {
                    QDLog.warn(logger, { "Failed to update file $relToBase" }, e)
                    project.service<ToolWindowService>()
                        .addToolingMessage("Modify File - failed", e.message ?: "Write action failed")
                    throw e
                }
            }
        }

        try {
            if (validateBuildAfterUpdate) {
                flushPsiAndVfs(project, updatedVirtualFile)
            } else {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                FileDocumentManager.getInstance()
                    .saveAllDocuments()
                val ioFile = resolved.toFile()
                val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                if (vFile != null) {
                    try {
                        VfsUtil.markDirtyAndRefresh(true, true, true, vFile)
                    } catch (_: Throwable) {
                    }
                    updatedVirtualFile = vFile
                    try {
                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (e: Throwable) {
            QDLog.debug(logger) { "Post-write sync failed: ${e.message}" }
        }

        if (validateAfterUpdate) {
            result += "\n" + runPsiValidation(project, relToBase)
        }
        QDLog.info(logger) { "Update file $relToBase: $result, file version: $lastModified" }
        return result
    }

    private fun runPsiValidation(
        project: Project,
        relToBase: String,
    ): String {
        return try {
            val validator = ValidateClassFileTool().apply { filePath = relToBase }
            val errors =
                ApplicationManager.getApplication().runReadAction<List<String>> { validator.findErrors(project) }
            val summary =
                if (errors.size == 1 && errors.first().equals("No compilation errors found.", true)) {
                    "Validation: No compilation errors found."
                } else if (errors.isEmpty()) {
                    "Validation: completed, no errors reported."
                } else {
                    val joined =
                        errors.joinToString("\n")
                    "Validation: " + (if (joined.length > 2000) joined.take(2000) + "\n..." else joined)
                }
            summary.lines().first()
        } catch (e: Throwable) {
            QDLog.warn(logger, { "Validation unavailable for $relToBase" }, e)
            "Validation: skipped (${e.message})"
        }
    }
}
