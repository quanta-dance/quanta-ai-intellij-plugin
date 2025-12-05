// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.project.VersionUtil
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
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

@JsonClassDescription(
    "Apply one or more line-range patches to a specified file. Patches are applied in a single write action, " +
        "from bottom to top (descending start line), so earlier replacements do not shift later ranges. " +
        "Lines are 1-based inclusive; offsets are computed from the current Document. Supports optional guards.",
)
class PatchFile : ToolInterface<String> {
    data class Patch(
        @field:JsonPropertyDescription("1-based start line (inclusive)")
        var fromLine: Int = 1,
        @field:JsonPropertyDescription("1-based end line (inclusive)")
        var toLine: Int = 1,
        @field:JsonPropertyDescription("Replacement content for the specified line range")
        var newContent: String = "",
        @field:JsonPropertyDescription(
            "Optional expected current text for the specified line range. " +
                "If provided and does not match, patch is skipped or triggers failure depending on stopOnMismatch.",
        )
        var expectedText: String? = null,
    )

    @field:JsonPropertyDescription("Relative to the project root path to the requested file.")
    var filePath: String? = null

    @field:JsonPropertyDescription("List of line-range patches to apply. Each patch uses 1-based lines inclusive.")
    var patches: List<Patch>? = null

    @field:JsonPropertyDescription("If true, validates the updated file after write and reports compilation errors.")
    var validateAfterUpdate: Boolean = false

    @field:JsonPropertyDescription(
        "If true (default), aborts and applies nothing when any patch guard fails. " +
            "If false, skips only mismatched patches and applies the rest.",
    )
    var stopOnMismatch: Boolean = true

    @field:JsonPropertyDescription(
        "Optional expected file version (PSI/Document/VFS) before patching. " +
            "If present and does not match, no changes are applied.",
    )
    var expectedFileVersion: Long? = null

    companion object {
        private val logger = Logger.getInstance(PatchFile::class.java)
    }

    private fun normalizeForCompare(text: String): String {
        // Normalize line endings to \n and ignore a single trailing newline for comparison
        val lf = text.replace("\r\n", "\n").replace("\r", "\n")
        return if (lf.endsWith("\n")) lf.dropLast(1) else lf
    }

    override fun execute(project: Project): String {
        val projectBase = project.basePath ?: return "Project base path not found."
        val resolved =
            try {
                PathUtils.resolveWithinProject(projectBase, filePath)
            } catch (e: IllegalArgumentException) {
                project.service<ToolWindowService>().addToolingMessage("Patch File - rejected", e.message ?: "Invalid path")
                return e.message ?: "Invalid path"
            }
        val relToBase = PathUtils.relativizeToProject(projectBase, resolved)
        val patchList = patches?.toList().orEmpty()
        if (patchList.isEmpty()) return "No patches provided."

        var result = StringBuilder()
        var lastModified: Long = 0

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val ioFile = resolved.toFile()
                ioFile.parentFile?.mkdirs()
                val parentVf =
                    try {
                        VfsUtil.createDirectories(ioFile.parentFile.absolutePath)
                    } catch (_: Throwable) {
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile.parentFile)
                            ?: throw IllegalStateException("Unable to create directories for: ${ioFile.parentFile}")
                    }
                val vFile = parentVf.findChild(ioFile.name) ?: parentVf.createChildData(this, ioFile.name)

                val docManager = FileDocumentManager.getInstance()
                val document = docManager.getDocument(vFile)
                if (document == null) {
                    result.append("Document not found; cannot apply line-range patches.")
                    project.service<ToolWindowService>()
                        .addToolingMessage("Patch File - failed", "$relToBase (no document)")
                } else {
                    val psiFile = PsiManager.getInstance(project).findFile(vFile)
                    val psiStamp = psiFile?.modificationStamp ?: 0L
                    val docStamp = docManager.getDocument(vFile)?.modificationStamp ?: 0L
                    val vfsStamp =
                        try {
                            vFile.modificationStamp
                        } catch (_: Throwable) {
                            0L
                        }
                    val currentVersion = VersionUtil.computeVersion(psiStamp, docStamp, vfsStamp)
                    if (expectedFileVersion != null && expectedFileVersion!! != currentVersion) {
                        result.append("Version mismatch: expected=")
                            .append(expectedFileVersion)
                            .append(", actual=")
                            .append(currentVersion)
                            .append(". No changes applied.")
                        return@runWriteCommandAction
                    }

                    val sorted =
                        patchList.sortedWith(compareByDescending<Patch> { it.fromLine }.thenByDescending { it.toLine })

                    // Validation pass if stopOnMismatch = true
                    if (stopOnMismatch) {
                        val mismatches = mutableListOf<String>()
                        for ((index, p) in sorted.withIndex()) {
                            val startLine = (p.fromLine - 1).coerceAtLeast(0)
                            val endLine = (p.toLine - 1).coerceAtLeast(startLine)
                            if (startLine >= document.lineCount) {
                                mismatches.add(
                                    "Patch ${index + 1}: start line ${p.fromLine} beyond document line count ${document.lineCount}",
                                )
                                continue
                            }
                            val startOffset = document.getLineStartOffset(startLine)
                            val endOffset =
                                if (endLine < document.lineCount) document.getLineEndOffset(endLine) else document.textLength
                            val currentSlice = document.getText(TextRange(startOffset, endOffset))
                            if (p.expectedText != null) {
                                val exp = normalizeForCompare(p.expectedText!!)
                                val cur = normalizeForCompare(currentSlice)
                                if (exp != cur) {
                                    mismatches.add("Patch ${index + 1}: expectedText mismatch at lines ${p.fromLine}-${p.toLine}")
                                }
                            }
                        }
                        if (mismatches.isNotEmpty()) {
                            result.append("Patched 0 range(s) in ").append(relToBase)
                                .append(" with ").append(mismatches.size)
                                .append(" mismatch(es). Aborted due to stopOnMismatch=true. ")
                                .append("Details: \n").append(mismatches.joinToString("\n"))
                            return@runWriteCommandAction
                        }
                    }

                    // Apply patches (skip mismatches if stopOnMismatch=false)
                    var applied = 0
                    val mismatches = mutableListOf<String>()
                    for ((index, p) in sorted.withIndex()) {
                        val startLine = (p.fromLine - 1).coerceAtLeast(0)
                        val endLine = (p.toLine - 1).coerceAtLeast(startLine)
                        if (startLine >= document.lineCount) {
                            mismatches.add("Patch ${index + 1}: start line ${p.fromLine} beyond document line count ${document.lineCount}")
                            continue
                        }
                        val startOffset = document.getLineStartOffset(startLine)
                        val endOffset =
                            if (endLine < document.lineCount) document.getLineEndOffset(endLine) else document.textLength
                        val currentSlice = document.getText(TextRange(startOffset, endOffset))
                        if (p.expectedText != null) {
                            val exp = normalizeForCompare(p.expectedText!!)
                            val cur = normalizeForCompare(currentSlice)
                            if (exp != cur) {
                                mismatches.add("Patch ${index + 1}: expectedText mismatch at lines ${p.fromLine}-${p.toLine}")
                                continue
                            }
                        }
                        document.replaceString(startOffset, endOffset, p.newContent)
                        applied++
                    }

                    try {
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                    } catch (_: Throwable) {
                    }
                    docManager.saveDocument(document)
                    try {
                        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vFile), true)
                    } catch (_: Throwable) {
                    }
                    lastModified = PsiManager.getInstance(project).findFile(vFile)?.modificationStamp ?: 0

                    if (mismatches.isEmpty()) {
                        result.append("Patched ").append(applied).append(" range(s) in ").append(relToBase)
                    } else {
                        result.append("Patched ").append(applied).append(" range(s) in ").append(relToBase)
                            .append(" with ").append(mismatches.size).append(" mismatch(es). Details: \n")
                            .append(mismatches.joinToString("\n"))
                    }
                }
            }
        }

        // Sync VFS and PSI
        try {
            FileDocumentManager.getInstance().saveAllDocuments()
            val ioFile = resolved.toFile()
            val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            if (vFile != null) {
                VfsUtil.markDirtyAndRefresh(true, true, true, vFile)
                try {
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }

        // Optional validation
        if (validateAfterUpdate) {
            try {
                val validator = ValidateClassFileTool().apply { filePath = relToBase }
                val errors =
                    ApplicationManager.getApplication().runReadAction<List<String>> { validator.findErrors(project) }
                val summary =
                    if (errors.size == 1 && errors.first().equals("No compilation errors found.", true)) {
                        "No compilation errors found."
                    } else if (errors.isEmpty()) {
                        "Validation completed, no errors reported."
                    } else {
                        errors.joinToString("\n").let { if (it.length > 2000) it.take(2000) + "\n..." else it }
                    }
                result.append("\nValidation: ").append(summary.lines().first())
            } catch (e: Throwable) {
                result.append("\nValidation: skipped (").append(e.message).append(")")
                QDLog.warn(logger, { "Validation unavailable for $relToBase" }, e)
            }
        }

        val summary = result.toString()
        project.service<ToolWindowService>().addToolingMessage("Patch file", "$relToBase\n$summary")
        return "$summary. File version: $lastModified"
    }
}
