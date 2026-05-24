// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.ToolFriendlyException
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolExecutionPresentation
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolPresentationProvider
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import java.security.MessageDigest

/**
 * Backend tool for guarded, line-oriented file patching.
 *
 * It is the lowest-level text editing primitive exposed to agents when they need atomic, expected-
 * text-checked document updates without replacing the whole file.
 */
@JsonClassDescription(
    "Apply one or more line-range patches to a specified file. Patches are applied in a single write action, " +
        "from bottom to top (descending start line), so earlier replacements do not shift later ranges. " +
        "Lines are 1-based inclusive; offsets are computed from the current Document. Supports optional guards.",
)
class PatchFile :
    ToolInterface<String>,
    ToolPresentationProvider {
    data class Patch
        @JsonCreator
        constructor(
            @param:JsonProperty("fromLine")
            @field:JsonPropertyDescription("1-based start line (inclusive)")
            var fromLine: Int = 1,
            @param:JsonProperty("toLine")
            @field:JsonPropertyDescription("1-based end line (inclusive)")
            var toLine: Int = 1,
            @param:JsonProperty("newContent")
            @field:JsonPropertyDescription("Replacement content for the specified line range")
            var newContent: String = "",
            @param:JsonProperty("expectedText")
            @field:JsonPropertyDescription(
                "Optional expected current text for the specified line range. " +
                    "If provided and does not match, patch is skipped or triggers failure depending on stopOnMismatch.",
            )
            var expectedText: String? = null,
        )

    override fun presentation(status: ToolExecutionStatus): ToolExecutionPresentation =
        ToolExecutionPresentation(
            title =
                filePath?.trim()?.takeIf { it.isNotBlank() }?.let { path ->
                    "Patching ${path.substringAfterLast('/').substringAfterLast('\\')}"
                } ?: "Patching file",
        )

    @field:JsonPropertyDescription("Relative to the project root path to the requested file.")
    var filePath: String? = null

    @field:JsonPropertyDescription("List of line-range patches to apply. Each patch uses 1-based lines inclusive.")
    var patches: List<Patch>? = null

    @field:JsonPropertyDescription(
        "If true, validates the updated file after write and reports compilation errors. " +
            "Enable this after each meaningful batch of edits to catch broken intermediate states early.",
    )
    var validateAfterUpdate: Boolean = false

    @field:JsonPropertyDescription(
        "If true (default), aborts and applies nothing when any patch guard fails. " +
            "If false, skips only mismatched patches and applies the rest. For iterative coding changes, keep this true unless you explicitly want partial application.",
    )
    var stopOnMismatch: Boolean = true

    @field:JsonPropertyDescription(
        "Optional expected SHA-256 hash of normalized file content (\\r\\n/\\r -> \\n)." +
            " If provided and matches current, patches can proceed.",
    )
    var expectedFileHashSha256: String? = null

    @field:JsonPropertyDescription(
        "If true, proceed when all patches' expectedText guards match even if content hash mismatches. " +
            "Default: true",
    )
    var allowProceedIfGuardsMatch: Boolean = true

    // Overlap guard
    @field:JsonPropertyDescription("If true (default), abort when any two patches overlap in line range. Helps avoid ambiguous edits.")
    var rejectOverlappingPatches: Boolean = true

    // PSI post-processing
    @field:JsonPropertyDescription("If true, reformat the PSI file after update.")
    var reformatAfterUpdate: Boolean = false

    @field:JsonPropertyDescription("If true, optimize imports after update.")
    var optimizeImportsAfterUpdate: Boolean = false

    @field:JsonPropertyDescription(
        "Soft window radius in lines for expectedText matching. If expectedText does not match exactly at fromLine..toLine, " +
            "the tool will search within +/- this many lines for a unique match and apply the patch there (if allowed). " +
            "Keep this small to avoid accidental relocation to the wrong code block. Default: 50.",
    )
    var softWindowRadiusLines: Int = 50

    @field:JsonPropertyDescription(
        "If true (default), include a truncated copy of the current text at fromLine..toLine in mismatch feedback to help recovery.",
    )
    var includeActualSliceOnMismatch: Boolean = true

    @field:JsonPropertyDescription("Maximum characters of actual slice to include in mismatch feedback. Default: 2000")
    var actualSliceMaxChars: Int = 2000

    @field:JsonPropertyDescription(
        "Minimum normalized expectedText length required to allow soft-window relocation. Default: 80.",
    )
    var minExpectedTextCharsForRelocation: Int = 80

    @field:JsonPropertyDescription(
        "If true (default), require expectedText to span at least 2 lines to allow relocation. Helps prevent wrong matches. " +
            "This guards against one-line matches that are too ambiguous.",
    )
    var requireMultilineExpectedTextForRelocation: Boolean = true

    companion object {
        private val logger = Logger.getInstance(PatchFile::class.java)
    }

    private fun normalizeForCompare(text: String): String {
        // Guard normalization should be stable across platforms and resilient to trivial formatting.
        // We intentionally keep it conservative: normalize line endings and trim trailing whitespace per line.
        var lf = text.replace("\r\n", "\n").replace("\r", "\n")
        if (lf.endsWith("\n")) lf = lf.dropLast(1)

        // Remove trailing spaces/tabs at end of each line to avoid frequent false mismatches.
        // Do NOT trim leading whitespace or collapse internal spaces (that could hide real code changes).
        return lf
            .split("\n")
            .joinToString("\n") { line -> line.replace(Regex("[\\t ]+$"), "") }
    }

    private fun normalizeSingleLineLeadingIndent(text: String): String {
        val normalized = normalizeForCompare(text)
        if (normalized.contains("\n")) return normalized
        return normalized.replace(Regex("^[\\t ]+"), "")
    }

    private fun preview(
        text: String,
        maxChars: Int = 200,
    ): String {
        val oneLine = normalizeForCompare(text).replace("\n", "\\n")
        return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "…"
    }

    private fun firstDifferenceSummary(
        expected: String,
        actual: String,
        baseStartLine1: Int,
    ): String {
        val expectedLines = normalizeForCompare(expected).split("\n")
        val actualLines = normalizeForCompare(actual).split("\n")
        val firstDiffIndex =
            (0 until maxOf(expectedLines.size, actualLines.size)).firstOrNull { idx ->
                expectedLines.getOrNull(idx) != actualLines.getOrNull(idx)
            } ?: return ""
        val expectedLine = expectedLines.getOrNull(firstDiffIndex).orEmpty()
        val actualLine = actualLines.getOrNull(firstDiffIndex).orEmpty()
        val expectedChar =
            (0 until maxOf(expectedLine.length, actualLine.length)).firstOrNull { i ->
                expectedLine.getOrNull(i) != actualLine.getOrNull(i)
            } ?: 0
        return " firstDiff=line ${baseStartLine1 + firstDiffIndex}, char ${expectedChar + 1}, expectedLine='${
            preview(
                expectedLine,
                160,
            )
        }', actualLine='${preview(actualLine, 160)}'"
    }

    private fun sha256Normalized(text: String): String {
        val norm = text.replace("\r\n", "\n").replace("\r", "\n")
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(norm.toByteArray()).joinToString("") { b -> "%02x".format(b) }
    }

    private data class ResolvedRange(
        val startLine0: Int,
        val endLine0: Int,
        val startOffset: Int,
        val endOffset: Int,
        val relocated: Boolean,
        val note: String? = null,
    )

    private fun lineEndOffsetOrEof(
        document: Document,
        line0: Int,
    ): Int {
        // Include the line separator after the end line when possible.
        // getLineEndOffset(line) is before the line separator, so using it for line-range replacement
        // can leave the original newline in place. If newContent also ends with "\n", this produces
        // a double-newline (extra blank line). Using the start offset of the next line includes the separator.
        return if (line0 + 1 < document.lineCount) {
            document.getLineStartOffset(line0 + 1)
        } else {
            document.textLength
        }
    }

    private fun sliceForLines(
        document: Document,
        startLine0: Int,
        endLine0: Int,
    ): Pair<TextRange, String> {
        val startOffset = document.getLineStartOffset(startLine0)
        val endOffset = lineEndOffsetOrEof(document, endLine0)
        val range = TextRange(startOffset, endOffset)
        return range to document.getText(range)
    }

    private fun resolveRange(
        document: Document,
        patch: Patch,
        patchIndex1: Int,
        mismatchesOut: MutableList<String>?,
        relocationNotesOut: MutableList<String>?,
    ): ResolvedRange? {
        val baseStartLine0 = (patch.fromLine - 1).coerceAtLeast(0)
        val baseEndLine0 = (patch.toLine - 1).coerceAtLeast(baseStartLine0)

        if (baseStartLine0 >= document.lineCount) {
            mismatchesOut?.add(
                "Patch $patchIndex1: start line ${patch.fromLine} beyond document line count ${document.lineCount}",
            )
            return null
        }

        val expectedRaw = patch.expectedText?.takeIf { it.isNotBlank() }
        val (baseRange, baseSlice) = sliceForLines(document, baseStartLine0, baseEndLine0)

        if (expectedRaw == null) {
            return ResolvedRange(
                startLine0 = baseStartLine0,
                endLine0 = baseEndLine0,
                startOffset = baseRange.startOffset,
                endOffset = baseRange.endOffset,
                relocated = false,
            )
        }

        val expNorm = normalizeForCompare(expectedRaw)
        val baseNorm = normalizeForCompare(baseSlice)
        if (expNorm == baseNorm) {
            return ResolvedRange(
                startLine0 = baseStartLine0,
                endLine0 = baseEndLine0,
                startOffset = baseRange.startOffset,
                endOffset = baseRange.endOffset,
                relocated = false,
            )
        }

        val expLineCount = expNorm.split("\n").size
        val baseLineCount = baseNorm.split("\n").size
        val indentationOnlySingleLineMismatch =
            expLineCount == 1 &&
                baseLineCount == 1 &&
                normalizeSingleLineLeadingIndent(expectedRaw) == normalizeSingleLineLeadingIndent(baseSlice)
        if (indentationOnlySingleLineMismatch) {
            relocationNotesOut?.add(
                "Patch $patchIndex1 accepted at ${patch.fromLine}-${patch.toLine} after indentation-only single-line guard normalization",
            )
            return ResolvedRange(
                startLine0 = baseStartLine0,
                endLine0 = baseEndLine0,
                startOffset = baseRange.startOffset,
                endOffset = baseRange.endOffset,
                relocated = false,
            )
        }

        // Soft-window relocation: find a unique match of expectedText nearby.
        val expLines = expNorm.split("\n")
        val expChars = expNorm.length
        val radius = softWindowRadiusLines.coerceAtLeast(0)

        val relocationAllowed =
            (!requireMultilineExpectedTextForRelocation || expLineCount >= 2) &&
                (expChars >= minExpectedTextCharsForRelocation || expLineCount >= 2)

        if (!relocationAllowed) {
            val reason =
                "relocation disabled (expectedText not specific enough: lines=$expLineCount chars=$expChars; " +
                    "requireMultiline=$requireMultilineExpectedTextForRelocation minChars=$minExpectedTextCharsForRelocation)"
            val actualExtra =
                if (includeActualSliceOnMismatch) {
                    val raw = baseSlice
                    val clipped = if (raw.length <= actualSliceMaxChars) raw else raw.take(actualSliceMaxChars) + "…"
                    " actualSlice='" + clipped.replace("\n", "\\n") + "'"
                } else {
                    ""
                }
            mismatchesOut?.add(
                "Patch $patchIndex1: expectedText mismatch at lines ${patch.fromLine}-${patch.toLine} ($reason). " +
                    "expected='${preview(expectedRaw)}' actual='${preview(baseSlice)}'" +
                    firstDifferenceSummary(expectedRaw, baseSlice, patch.fromLine) +
                    actualExtra,
            )
            return null
        }

        val windowStart0 = (baseStartLine0 - radius).coerceAtLeast(0)
        val windowEnd0 = (baseEndLine0 + radius).coerceAtMost((document.lineCount - 1).coerceAtLeast(0))

        val matches = mutableListOf<ResolvedRange>()
        var candStart0 = windowStart0
        while (candStart0 <= windowEnd0) {
            val candEnd0 = candStart0 + expLineCount - 1
            if (candEnd0 > windowEnd0) break

            val (candRange, candSlice) = sliceForLines(document, candStart0, candEnd0)
            val candNorm = normalizeForCompare(candSlice)
            if (candNorm == expNorm) {
                matches.add(
                    ResolvedRange(
                        startLine0 = candStart0,
                        endLine0 = candEnd0,
                        startOffset = candRange.startOffset,
                        endOffset = candRange.endOffset,
                        relocated = true,
                        note = "Patch $patchIndex1 relocated from ${patch.fromLine}-${patch.toLine} to ${candStart0 + 1}-${candEnd0 + 1}",
                    ),
                )
                if (matches.size > 1) break
            }
            candStart0++
        }

        if (matches.size == 1) {
            val rr = matches.first()
            relocationNotesOut?.add(rr.note ?: "")
            return rr
        }

        val reason =
            if (matches.isEmpty()) {
                "no match in soft window +/-$radius lines"
            } else {
                "ambiguous: ${matches.size} matches in soft window +/-$radius lines"
            }

        val actualExtra =
            if (includeActualSliceOnMismatch) {
                val raw = baseSlice
                val clipped = if (raw.length <= actualSliceMaxChars) raw else raw.take(actualSliceMaxChars) + "…"
                " actualSlice='" + clipped.replace("\n", "\\n") + "'"
            } else {
                ""
            }
        mismatchesOut?.add(
            "Patch $patchIndex1: expectedText mismatch at lines ${patch.fromLine}-${patch.toLine} ($reason). " +
                "expected='${preview(expectedRaw)}' actual='${preview(baseSlice)}'" +
                firstDifferenceSummary(expectedRaw, baseSlice, patch.fromLine) +
                actualExtra,
        )
        return null
    }

    private data class Range(
        val from: Int,
        val to: Int,
        val index: Int,
    )

    private fun findOverlaps(ranges: List<Range>): List<Pair<Range, Range>> {
        if (ranges.size < 2) return emptyList()
        val sorted = ranges.sortedWith(compareBy<Range> { it.from }.thenBy { it.to })
        val overlaps = mutableListOf<Pair<Range, Range>>()
        var prev: Range? = null
        for (r in sorted) {
            val p = prev
            if (p != null) {
                // Overlap if r.from <= p.to (inclusive ranges)
                if (r.from <= p.to) overlaps.add(p to r)
                if (r.to > (p.to)) prev = if (r.to >= p.to) r else p else prev = r
            } else {
                prev = r
            }
        }
        return overlaps
    }

    override fun execute(project: Project): String {
        val projectBase = PathUtils.projectRootPath(project) ?: return "Project base path not found."
        val resolved =
            try {
                PathUtils.resolveWithinProject(project, filePath)
            } catch (e: IllegalArgumentException) {
                return e.message ?: "Invalid path"
            }
        val relToBase = PathUtils.relativizeToProject(projectBase, resolved)
        val patchList = patches?.toList().orEmpty()
        if (patchList.isEmpty()) return "No patches provided."

        var result = StringBuilder()
        var lastModified: Long = 0

        try {
            ApplicationManager.getApplication().invokeAndWait {
                WriteCommandAction.runWriteCommandAction(project) {
                    val vFile =
                        PathUtils.resolveVirtualFileWithinProject(project, relToBase)
                            ?: run {
                                return@runWriteCommandAction
                            }

                    val docManager = FileDocumentManager.getInstance()
                    val document =
                        try {
                            docManager.getDocument(vFile)
                                ?: throw ToolFriendlyException(
                                    "Failed to obtain editable document for $relToBase.",
                                    code = "patch_failed",
                                    retriable = false,
                                )
                        } catch (e: Throwable) {
                            throw wrapPatchWriteFailure(relToBase, "obtain editable document", e)
                        }

                    val sorted =
                        patchList
                            .mapIndexed { idx, p -> Range(p.fromLine, p.toLine, idx) }
                            .sortedWith(compareByDescending<Range> { it.from }.thenBy { it.to })

                    if (rejectOverlappingPatches) {
                        val overlaps = findOverlaps(sorted)
                        if (overlaps.isNotEmpty()) {
                            result.append("Rejected: overlapping patches: ")
                            result.append(overlaps.joinToString("; ") { "${it.first.index}-${it.second.index}" })
                            return@runWriteCommandAction
                        }
                    }

                    val relocationNotes = mutableListOf<String>()

                    val normalized = sha256Normalized(document.text)
                    val hashProvided = expectedFileHashSha256 != null
                    val hashMatched = !hashProvided || expectedFileHashSha256 == normalized

                    if (hashProvided && !hashMatched && !allowProceedIfGuardsMatch) {
                        result
                            .append("Aborted: Content hash mismatch for ")
                            .append(relToBase)
                            .append(" and guards did not allow proceed.")
                        return@runWriteCommandAction
                    }

                    // Preflight: resolve all patches first so we can abort without partial application.
                    val resolved = mutableListOf<Pair<Patch, ResolvedRange>>()
                    val mismatches = mutableListOf<String>()
                    for ((index, r) in sorted.withIndex()) {
                        val patch = patchList[r.index]
                        val rr = resolveRange(document, patch, index + 1, mismatches, relocationNotes)
                        if (rr == null) {
                            if (stopOnMismatch) {
                                result
                                    .append("Aborted: Patched 0 range(s) in ")
                                    .append(relToBase)
                                    .append(" with ")
                                    .append(mismatches.size)
                                    .append(" mismatch(es). Details: \n")
                                    .append(mismatches.joinToString("\n"))
                                return@runWriteCommandAction
                            }

                            continue
                        }
                        resolved.add(patch to rr)
                    }

                    var applied = 0
                    var alreadyApplied = 0
                    for ((patch, rr) in resolved) {
                        val effectiveNewContent =
                            if (rr.endOffset < document.textLength && !patch.newContent.endsWith("\n")) {
                                patch.newContent + "\n"
                            } else {
                                patch.newContent
                            }

                        // Idempotence: if the resolved slice already equals newContent (after normalization), skip.
                        val currentSlice = document.getText(TextRange(rr.startOffset, rr.endOffset))
                        if (normalizeForCompare(currentSlice) == normalizeForCompare(effectiveNewContent)) {
                            alreadyApplied++
                            continue
                        }

                        try {
                            document.replaceString(rr.startOffset, rr.endOffset, effectiveNewContent)
                            applied++
                        } catch (e: Throwable) {
                            throw wrapPatchWriteFailure(relToBase, "apply patch text", e)
                        }
                    }

                    try {
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                    } catch (e: Throwable) {
                        throw wrapPatchWriteFailure(relToBase, "commit patched document", e)
                    }
                    try {
                        docManager.saveDocument(document)
                    } catch (e: Throwable) {
                        throw wrapPatchWriteFailure(relToBase, "save patched document", e)
                    }

                    if (reformatAfterUpdate || optimizeImportsAfterUpdate) {
                        try {
                            val psi = PsiManager.getInstance(project).findFile(vFile)
                            if (psi != null) {
                                if (reformatAfterUpdate) CodeStyleManager.getInstance(project).reformat(psi)
                                if (optimizeImportsAfterUpdate) OptimizeImportsProcessor(project, psi).run()
                            }
                        } catch (_: Throwable) {
                        }
                    }

                    if (mismatches.isEmpty()) {
                        result
                            .append("Patched ")
                            .append(applied)
                            .append(" range(s) in ")
                            .append(relToBase)
                    } else {
                        result
                            .append("Patched ")
                            .append(applied)
                            .append(" range(s) in ")
                            .append(relToBase)
                            .append(" with ")
                            .append(mismatches.size)
                            .append(" mismatch(es). Details: \n")
                            .append(mismatches.joinToString("\n"))
                    }
                    if (alreadyApplied > 0) {
                        result.append("\nNo-op: ").append(alreadyApplied).append(" range(s) already matched newContent")
                    }
                    if (relocationNotes.isNotEmpty()) {
                        result.append("\nRelocations:\n").append(relocationNotes.distinct().joinToString("\n"))
                    }
                }
            }
        } catch (e: Throwable) {
            val rootCause = rootCauseOf(e)
            if (rootCause is ToolFriendlyException) {
                throw rootCause
            }
            if (rootCause is com.intellij.openapi.progress.ProcessCanceledException ||
                rootCause is java.util.concurrent.CancellationException
            ) {
                val cancelDetail = rootCause.message?.trim().orEmpty()
                val cancelMessage =
                    if (cancelDetail.isBlank() ||
                        cancelDetail.equals(
                            "Cancelled by Message.Cancel",
                            ignoreCase = true,
                        )
                    ) {
                        "Patch execution for $relToBase was cancelled by the environment before completion."
                    } else {
                        "Patch execution for $relToBase was cancelled before completion: $cancelDetail"
                    }
                throw ToolFriendlyException(cancelMessage, code = "cancelled", retriable = true)
            }
            throw ToolFriendlyException(
                "Failed to apply patch to $relToBase${formatCauseSuffix(rootCause)}",
                code = "patch_failed",
                retriable = false,
            )
        }

        try {
            FileDocumentManager.getInstance().saveAllDocuments()
            val vFile = PathUtils.resolveVirtualFileWithinProject(project, relToBase)
            if (vFile != null) {
                VfsUtil.markDirtyAndRefresh(true, true, true, vFile)
                try {
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }

        var fileHashSha256: String? = null
        try {
            val vFile = PathUtils.resolveVirtualFileWithinProject(project, relToBase)
            if (vFile != null) {
                val currentText =
                    ApplicationManager.getApplication().runReadAction<String> {
                        FileDocumentManager.getInstance().getDocument(vFile)?.text
                            ?: vFile.inputStream.bufferedReader().use { it.readText() }
                    }
                fileHashSha256 = sha256Normalized(currentText)
            }
        } catch (_: Throwable) {
        }

        if (validateAfterUpdate) {
            try {
                if (shouldRunPsiValidation(relToBase)) {
                    val validator = ValidateClassFileTool().apply { filePath = relToBase }
                    val errors =
                        ApplicationManager
                            .getApplication()
                            .runReadAction<List<String>> { validator.findErrors(project) }
                    val summary =
                        if (errors.size == 1 && errors.first().equals("No compilation errors found.", true)) {
                            "No compilation errors found."
                        } else if (errors.isEmpty()) {
                            "Validation completed, no errors reported."
                        } else {
                            errors.joinToString("\n").let { if (it.length > 2000) it.take(2000) + "\n..." else it }
                        }
                    result.append("\nValidation: ").append(summary.lines().first())
                } else {
                    result.append("\nValidation: skipped (").append(validationSkipReason(relToBase)).append(")")
                }
            } catch (e: Throwable) {
                result.append("\nValidation: skipped (").append(e.message).append(")")
                QDLog.warn(logger, { "Validation unavailable for $relToBase" }, e)
            }
        }

        if (!fileHashSha256.isNullOrBlank()) {
            result.append("\nfileHashSha256=").append(fileHashSha256)
        }

        return result.toString()
    }

    private fun rootCauseOf(error: Throwable): Throwable {
        var current: Throwable = error
        val visited = HashSet<Throwable>()
        while (current.cause != null && visited.add(current)) {
            current = current.cause!!
        }
        return current
    }

    private fun formatCauseSuffix(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        return if (message.isBlank()) "" else ": $message"
    }

    private fun wrapPatchWriteFailure(
        relToBase: String,
        action: String,
        error: Throwable,
    ): ToolFriendlyException {
        val rootCause = rootCauseOf(error)
        if (rootCause is ToolFriendlyException) return rootCause
        val message = rootCause.message?.trim().orEmpty()
        val suffix = if (message.isBlank()) "" else ": $message"
        return ToolFriendlyException(
            message = "Failed to $action for $relToBase$suffix",
            code = "patch_failed",
            retriable = false,
        )
    }

    private fun shouldRunPsiValidation(relToBase: String): Boolean {
        val ext = relToBase.substringAfterLast('.', "").lowercase()
        return ext in setOf("kt", "kts", "java", "scala", "groovy", "go")
    }

    private fun validationSkipReason(relToBase: String): String = "PSI validation not applicable for this file type"
}
