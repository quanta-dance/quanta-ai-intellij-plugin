// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.project.VersionUtil
import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.github.quanta_dance.quanta.plugins.intellij.tools.PathUtils
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
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
import com.intellij.psi.codeStyle.CodeStyleManager
import java.security.MessageDigest

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

    @Deprecated("Use expectedFileHashSha256 for content-stable preconditions.")
    @field:JsonPropertyDescription(
        "Deprecated: expected file version before patching. Use expectedFileHashSha256 instead.",
    )
    var expectedFileVersion: Long? = null

    @field:JsonPropertyDescription(
        "Optional expected SHA-256 hash of normalized file content (\\r\\n/\\r -> \\n). If provided and matches current, patches can proceed.",
    )
    var expectedFileHashSha256: String? = null

    @field:JsonPropertyDescription("If true, proceed when all patches' expectedText guards match even if content hash mismatches. Default: true")
    var allowProceedIfGuardsMatch: Boolean = true

    // PSI post-processing
    @field:JsonPropertyDescription("If true, reformat the PSI file after update.")
    var reformatAfterUpdate: Boolean = false

    @field:JsonPropertyDescription("If true, optimize imports after update.")
    var optimizeImportsAfterUpdate: Boolean = false

    companion object {
        private val logger = Logger.getInstance(PatchFile::class.java)
    }

    private fun normalizeForCompare(text: String): String {
        val lf = text.replace("\r\n", "\n").replace("\r", "\n")
        return if (lf.endsWith("\n")) lf.dropLast(1) else lf
    }

    private fun sha256Normalized(text: String): String {
        val norm = text.replace("\r\n", "\n").replace("\r", "\n")
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(norm.toByteArray()).joinToString("") { b -> "%02x".format(b) }
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
                    project.service<ToolWindowService>().addToolingMessage("Patch File - failed", "$relToBase (no document)")
                } else {
                    // Global precondition via content hash if provided
                    val curHash = sha256Normalized(document.text)
                    val hashProvided = expectedFileHashSha256 != null
                    val hashMatched = !hashProvided || expectedFileHashSha256 == curHash

                    if (hashProvided && !hashMatched && !allowProceedIfGuardsMatch) {
                        result.append("Content hash mismatch. No changes applied.")
                        return@runWriteCommandAction
                    }

                    val sorted = patchList.sortedWith(compareByDescending<Patch> { it.fromLine }.thenByDescending { it.toLine })

                    if (stopOnMismatch || (hashProvided && !hashMatched && allowProceedIfGuardsMatch)) {
                        val mismatches = mutableListOf<String>()
                        for ((index, p) in sorted.withIndex()) {
                            val startLine = (p.fromLine - 1).coerceAtLeast(0)
                            val endLine = (p.toLine - 1).coerceAtLeast(startLine)
                            if (startLine >= document.lineCount) {
                                mismatches.add("Patch ${index + 1}: start line ${p.fromLine} beyond document line count ${document.lineCount}")
                                continue
                            }
                            val startOffset = document.getLineStartOffset(startLine)
                            val endOffset = if (endLine < document.lineCount) document.getLineEndOffset(endLine) else document.textLength
                            val currentSlice = document.getText(TextRange(startOffset, endOffset))
                            p.expectedText?.let { exp0 ->
                                val exp = normalizeForCompare(exp0)
                                val cur = normalizeForCompare(currentSlice)
                                if (exp != cur) mismatches.add("Patch ${index + 1}: expectedText mismatch at lines ${p.fromLine}-${p.toLine}")
                            }
                        }
                        if (mismatches.isNotEmpty() && stopOnMismatch) {
                            result.append("Patched 0 range(s) in ").append(relToBase).append(" with ").append(mismatches.size).append(" mismatch(es). Aborted due to stopOnMismatch=true. ")
                                .append("Details: \n").append(mismatches.joinToString("\n"))
                            return@runWriteCommandAction
                        }
                        if (mismatches.isNotEmpty() && hashProvided && !hashMatched && allowProceedIfGuardsMatch) {
                            result.append("Patched 0 range(s) in ").append(relToBase).append(" because guards mismatched under content hash mismatch. Details: \n").append(mismatches.joinToString("\n"))
                            return@runWriteCommandAction
                        }
                    }

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
                        val endOffset = if (endLine < document.lineCount) document.getLineEndOffset(endLine) else document.textLength
                        val currentSlice = document.getText(TextRange(startOffset, endOffset))
                        p.expectedText?.let { exp0 ->
                            val exp = normalizeForCompare(exp0)
                            val cur = normalizeForCompare(currentSlice)
                            if (exp != cur) {
                                mismatches.add("Patch ${index + 1}: expectedText mismatch at lines ${p.fromLine}-${p.toLine}")
                                if (stopOnMismatch) continue else { applied += 0; continue }
                            }
                        }
                        document.replaceString(startOffset, endOffset, p.newContent)
                        applied++
                    }

                    try { PsiDocumentManager.getInstance(project).commitDocument(document) } catch (_: Throwable) {}
                    docManager.saveDocument(document)

                    if (reformatAfterUpdate || optimizeImportsAfterUpdate) {
                        try {
                            val psi = PsiManager.getInstance(project).findFile(vFile)
                            if (psi != null) {
                                if (reformatAfterUpdate) CodeStyleManager.getInstance(project).reformat(psi)
                                if (optimizeImportsAfterUpdate) OptimizeImportsProcessor(project, psi).run()
                            }
                        } catch (_: Throwable) {}
                    }

                    try { FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vFile), true) } catch (_: Throwable) {}
                    lastModified = PsiManager.getInstance(project).findFile(vFile)?.modificationStamp ?: 0

                    if (mismatches.isEmpty()) {
                        result.append("Patched ").append(applied).append(" range(s) in ").append(relToBase)
                    } else {
                        result.append("Patched ").append(applied).append(" range(s) in ").append(relToBase).append(" with ").append(mismatches.size).append(" mismatch(es). Details: \n").append(mismatches.joinToString("\n"))
                    }
                }
            }
        }

        try {
            FileDocumentManager.getInstance().saveAllDocuments()
            val ioFile = resolved.toFile()
            val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            if (vFile != null) {
                VfsUtil.markDirtyAndRefresh(true, true, true, vFile)
                try { PsiDocumentManager.getInstance(project).commitAllDocuments() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        if (validateAfterUpdate) {
            try {
                val validator = ValidateClassFileTool().apply { filePath = relToBase }
                val errors = ApplicationManager.getApplication().runReadAction<List<String>> { validator.findErrors(project) }
                val summary = if (errors.size == 1 && errors.first().equals("No compilation errors found.", true)) {
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
