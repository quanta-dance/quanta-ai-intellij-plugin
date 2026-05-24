// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.ToolFriendlyException
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Backend tool for file creation, full replacement, or guarded line-range updates.
 *
 * This tool is the higher-level sibling of [PatchFile]: it supports whole-file writes as well as
 * small targeted patches while keeping validation, formatting, and import optimization in one place.
 */
@JsonClassDescription(
    "Create or update a file. Prefer patches for existing files; " +
        "use full replacement only for brand-new files or an explicitly intended wholesale rewrite. " +
        "When patching existing code, keep edits narrow and include expectedText/hash guards when possible. " +
        "Before modifying methods in a file, check references because callers may need updates.",
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
        "New content for the file to be modified. If provided and 'patches' is empty, this fully replaces file content. " +
            "Use this mainly for brand-new files or deliberate full-file rewrites; prefer patches for existing files.",
    )
    var content: String? = null

    @field:JsonPropertyDescription(
        "If true, validates the updated file after write and reports compilation errors. " +
            "This helps catch broken intermediate edits before the agent continues.",
    )
    var validateAfterUpdate: Boolean = true

    @field:JsonPropertyDescription(
        "Optional list of line-range patches to apply (1-based inclusive lines). If non-empty, " +
            "patches are applied instead of full replace.",
    )
    var patches: List<Patch>? = null

    @field:JsonPropertyDescription(
        "If true, force synchronous save/commit/refresh " +
            "to surface PSI errors immediately (no Gradle run). Default: true. " +
            "Use this for quick sanity checks after edits, but still re-read the file if the content looks wrong.",
    )
    var validateBuildAfterUpdate: Boolean = true

    // Pass-through guards for patch mode
    @field:JsonPropertyDescription(
        "If true (default), aborts and applies nothing when any patch guard fails. " +
            "Keep this true for multi-edit or high-risk changes so the tool does not silently drift.",
    )
    var stopOnMismatch: Boolean = true

    @field:JsonPropertyDescription(
        "Optional expected SHA-256 hash of normalized file content (\\r\\n/\\r -> \\n)." +
            " If provided and matches current, patches can proceed.",
    )
    var expectedFileHashSha256: String? = null

    // PSI post-processing
    @field:JsonPropertyDescription("If true, reformat the PSI file after update.")
    var reformatAfterUpdate: Boolean = false

    @field:JsonPropertyDescription("If true, optimize imports after update.")
    var optimizeImportsAfterUpdate: Boolean = false

    companion object {
        private val logger = Logger.getInstance(CreateOrUpdateFile::class.java)
    }

    private fun sha256Normalized(text: String): String {
        val norm = text.replace("\r\n", "\n").replace("\r", "\n")
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(norm.toByteArray()).joinToString("") { b -> "%02x".format(b) }
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
        val projectBase = PathUtils.projectRootPath(project) ?: return "Project base path not found."
        val resolved =
            try {
                PathUtils.resolveWithinProject(project, filePath)
            } catch (e: IllegalArgumentException) {
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
                    expectedFileHashSha256 = this@CreateOrUpdateFile.expectedFileHashSha256

                    reformatAfterUpdate = this@CreateOrUpdateFile.reformatAfterUpdate
                    optimizeImportsAfterUpdate = this@CreateOrUpdateFile.optimizeImportsAfterUpdate
                }
            val result = pf.execute(project)
            if (validateBuildAfterUpdate) {
                val vFile = PathUtils.resolveVirtualFileWithinProject(project, relToBase)
                flushPsiAndVfs(project, vFile)
            }
            return if (validateAfterUpdate) result + "\n" + runPsiValidation(project, relToBase) else result
        }

        var result: String = "File successfully updated"
        var fileHashSha256: String? = null
        var updatedVirtualFile: VirtualFile? = null

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    val rootVf =
                        PathUtils.projectRootVirtualFile(project)
                            ?: throw IllegalStateException("Project root not found")
                    val parentRel = relToBase.substringBeforeLast('/', "")
                    val fileName = relToBase.substringAfterLast('/')
                    val parentVf =
                        if (parentRel.isBlank()) {
                            rootVf
                        } else {
                            VfsUtil.createDirectories(rootVf.path + "/" + parentRel)
                        }
                    val virtualFile = parentVf.findChild(fileName) ?: parentVf.createChildData(this, fileName)
                    updatedVirtualFile = virtualFile

                    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                    if (document != null) {
                        try {
                            document.setText(content ?: "")
                            PsiDocumentManager
                                .getInstance(project)
                                .commitDocument(document)
                            FileDocumentManager.getInstance().saveDocument(document)
                        } catch (e: Throwable) {
                            throw wrapWriteFailure(relToBase, "set, commit, or save document", e)
                        }
                    } else {
                        try {
                            virtualFile.setBinaryContent((content ?: "").toByteArray(StandardCharsets.UTF_8))
                        } catch (e: Throwable) {
                            throw wrapWriteFailure(relToBase, "write file bytes", e)
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
                        val currentText =
                            ApplicationManager.getApplication().runReadAction<String> {
                                FileDocumentManager.getInstance().getDocument(virtualFile)?.text
                                    ?: virtualFile.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                            }
                        fileHashSha256 = sha256Normalized(currentText)
                    } catch (_: Throwable) {
                    }
                } catch (e: Throwable) {
                    val rootCause = rootCauseOf(e)
                    if (rootCause is ToolFriendlyException) {
                        throw rootCause
                    }
                    if (rootCause is ProcessCanceledException || rootCause is java.util.concurrent.CancellationException) {
                        val cancelMessage =
                            "Write operation was cancelled while updating $relToBase before completion. Retry may succeed."
                        throw ToolFriendlyException(cancelMessage, code = "cancelled", retriable = true)
                    }
                    QDLog.warn(logger, { "Failed to update file $relToBase" }, e)
                    throw wrapWriteFailure(relToBase, "update file", rootCause)
                }
            }
        }

        try {
            if (validateBuildAfterUpdate) {
                flushPsiAndVfs(project, updatedVirtualFile)
            } else {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                FileDocumentManager
                    .getInstance()
                    .saveAllDocuments()
                val vFile = updatedVirtualFile ?: PathUtils.resolveVirtualFileWithinProject(project, relToBase)
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
        if (!fileHashSha256.isNullOrBlank()) {
            result += "\nfileHashSha256=$fileHashSha256"
        }
        QDLog.info(logger) {
            "Update file $relToBase: $result, fileHashSha256=${fileHashSha256 ?: ""}"
        }
        return result
    }

    private fun wrapWriteFailure(
        relToBase: String,
        action: String,
        error: Throwable,
    ): ToolFriendlyException {
        val rootCause = rootCauseOf(error)
        val message = rootCause.message?.trim().orEmpty()
        val suffix = if (message.isNotBlank()) ": $message" else ""
        return ToolFriendlyException(
            message = "Failed to $action for $relToBase$suffix",
            code = "write_failed",
            retriable = false,
        )
    }

    private fun rootCauseOf(error: Throwable): Throwable {
        var current: Throwable = error
        val visited = HashSet<Throwable>()
        while (current.cause != null && visited.add(current)) {
            current = current.cause!!
        }
        return current
    }

    private fun runPsiValidation(
        project: Project,
        relToBase: String,
    ): String {
        if (!shouldRunPsiValidation(relToBase)) {
            return "Validation: skipped (${validationSkipReason(relToBase)})"
        }
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
            if (e is ProcessCanceledException || e is java.util.concurrent.CancellationException) {
                return "Validation: cancelled by environment before completion. Retry may succeed."
            }
            QDLog.warn(logger, { "Validation unavailable for $relToBase" }, e)
            "Validation: skipped (${e.message})"
        }
    }

    private fun shouldRunPsiValidation(relToBase: String): Boolean {
        val ext = relToBase.substringAfterLast('.', "").lowercase()
        return ext in setOf("kt", "kts", "java", "scala", "groovy") || (ext == "go" && isGoPluginInstalled())
    }

    private fun validationSkipReason(relToBase: String): String {
        val ext = relToBase.substringAfterLast('.', "").lowercase()
        return when {
            ext == "go" && !isGoPluginInstalled() -> "no validator available for Go files in this IDE (Go plugin not installed)"
            else -> "PSI validation not applicable for this file type"
        }
    }

    private fun isGoPluginInstalled(): Boolean =
        try {
            val pluginManagerCore = Class.forName("com.intellij.ide.plugins.PluginManagerCore")
            val pluginIdClass = Class.forName("com.intellij.openapi.extensions.PluginId")
            val getIdMethod = pluginIdClass.getMethod("getId", String::class.java)
            val pluginId = getIdMethod.invoke(null, "org.jetbrains.plugins.go")
            val isInstalledMethod = pluginManagerCore.getMethod("isPluginInstalled", pluginIdClass)
            isInstalledMethod.invoke(null, pluginId) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
}
