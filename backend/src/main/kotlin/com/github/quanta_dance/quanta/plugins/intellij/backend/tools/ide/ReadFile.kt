// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.project.CurrentFileContextProvider
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.models.ReadFileResult
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolExecutionPresentation
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolPresentationProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.isFile

/**
 * Backend tool for reading file content with optional slicing and truncation strategies.
 *
 * Compared with [ReadPsiBlockAtPosition], this tool stays text-oriented: it is best for whole-file or
 * line-range reads, while PSI-aware structural extraction belongs to the PSI block tool.
 */
@JsonClassDescription(
    "Read the content of a file. REQUIRED ARGUMENT: filePath. Use filePath, not path. " +
        "Example: {\"filePath\": \"README.md\"}. Supports optional truncation and windowed reading around " +
        "caret/selection for the current file.",
)
data class ReadFile
    @JsonCreator
    constructor(
        @param:JsonProperty("filePath")
        @field:JsonPropertyDescription(
            "REQUIRED. Use this exact field name: filePath. Relative to the project root path to the requested file. " +
                "Example: README.md",
        )
        val filePath: String,
        @param:JsonProperty("includeLineNumbers")
        @field:JsonPropertyDescription("If true, returns content with prefixed line numbers. Default false.")
        var includeLineNumbers: Boolean = false,
        @param:JsonProperty("maxChars")
        @field:JsonPropertyDescription(
            "Maximum characters to return; if exceeded, tool truncates per strategy. Default 6000.",
        )
        var maxChars: Int = 6_000,
        @param:JsonProperty("strategy")
        @field:JsonPropertyDescription(
            "Preferred truncation strategy when file exceeds maxChars: head | tail | window. Default window.",
        )
        var strategy: String = "window",
        @param:JsonProperty("preferWindowIfCurrentFile")
        @field:JsonPropertyDescription(
            "If true, and the file is the current editor file with caret/selection, " +
                "return a window around caret/selection when truncating.",
        )
        var preferWindowIfCurrentFile: Boolean = true,
        @param:JsonProperty("windowRadiusLines")
        @field:JsonPropertyDescription(
            "Window radius in lines (before and after caret or selection) when strategy=window. Default 300.",
        )
        var windowRadiusLines: Int = 300,
        @param:JsonProperty("fromLine")
        @field:JsonPropertyDescription(
            "Optional 1-based starting line (inclusive). If set, content is first sliced to start from this line. " +
                "When provided together with toLine, returns that exact line range. " +
                "Takes precedence over strategy/window behavior.",
        )
        var fromLine: Int? = null,
        @param:JsonProperty("toLine")
        @field:JsonPropertyDescription(
            "Optional 1-based ending line (inclusive). If set, content is first sliced to end at this line. " +
                "If set without fromLine, fromLine defaults to 1. " +
                "Takes precedence over strategy/window behavior.",
        )
        var toLine: Int? = null,
    ) : ToolInterface<ReadFileResult>,
        ToolPresentationProvider {
        override val canBeParallel: Boolean = true

        override fun presentation(status: ToolExecutionStatus): ToolExecutionPresentation =
            ToolExecutionPresentation(title = "Reading ${filePath.substringAfterLast('/').substringAfterLast('\\')}")

        companion object {
            private val logger = Logger.getInstance(ReadFile::class.java)
        }

        private fun withLineNumbers(
            fileContent: String,
            startLineNumber: Int = 1,
        ): String {
            val base = startLineNumber.coerceAtLeast(1)
            return fileContent
                .lines()
                .mapIndexed { index, line -> "%05d %s".format(base + index, line) }
                .joinToString("\n")
        }

        private fun clampWindow(
            start: Int,
            end: Int,
            lineCount: Int,
        ): Pair<Int, Int> {
            if (lineCount <= 0) return Pair(0, -1)
            val maxIndex = lineCount - 1
            val s = start.coerceIn(0, maxIndex)
            val e = end.coerceIn(s, maxIndex)
            return Pair(s, e)
        }

        private fun sliceByLines(
            raw: String,
            fromLine0: Int,
            toLine0: Int,
        ): String {
            val lines = raw.lines()
            if (lines.isEmpty()) return ""
            val (s, e) = clampWindow(fromLine0, toLine0, lines.size)
            if (e < s) return ""
            return lines.subList(s, e + 1).joinToString("\n")
        }

        private fun headByLines(
            raw: String,
            maxChars: Int,
        ): Pair<String, Int> {
            val lines = raw.lines()
            val out = StringBuilder()
            var count = 0
            var i = 0
            while (i < lines.size) {
                val add = if (out.isEmpty()) lines[i] else "\n" + lines[i]
                if (out.length + add.length > maxChars) break
                out.append(add)
                count++
                i++
            }
            return Pair(out.toString(), 0)
        }

        private fun tailByLines(
            raw: String,
            maxChars: Int,
        ): Pair<String, Int> {
            val lines = raw.lines()
            val out = StringBuilder()
            var count = 0
            var i = lines.lastIndex
            val stack = ArrayDeque<String>()
            while (i >= 0) {
                val add = if (stack.isEmpty()) lines[i] else lines[i] + "\n"
                if (out.length + add.length > maxChars) break
                stack.addFirst(lines[i])
                out.append(add)
                count++
                i--
            }
            val start0 = lines.size - count
            return Pair(stack.joinToString("\n"), start0.coerceAtLeast(0))
        }

        private fun sha256Normalized(raw: String): String = FileHashUtil.sha256Normalized(raw)

        private fun validateRange(
            fromLine: Int?,
            toLine: Int?,
        ): String? {
            if (fromLine == null && toLine == null) return null

            val start1 = fromLine ?: 1
            val end1 = toLine

            if (start1 <= 0) return "fromLine must be >= 1"
            if (end1 != null && end1 <= 0) return "toLine must be >= 1"
            if (end1 != null && end1 < start1) return "toLine must be >= fromLine"

            return null
        }

        private fun validateRangeAgainstFile(
            fromLine: Int?,
            toLine: Int?,
            lineCount: Int,
        ): String? {
            if (fromLine == null && toLine == null) return null
            if (lineCount <= 0) return "File is empty; requested line range cannot be read."

            val start1 = fromLine ?: 1
            if (start1 > lineCount) {
                return "Requested fromLine $start1 is out of range; file has only $lineCount lines."
            }

            return null
        }

        private fun buildRangeClampWarning(
            fromLine: Int?,
            toLine: Int?,
            lineCount: Int,
        ): String? {
            if (fromLine == null && toLine == null) return null
            if (lineCount <= 0) return null

            val end1 = toLine ?: return null
            if (end1 <= lineCount) return null

            return buildString {
                append("Requested toLine ")
                append(end1)
                append(" exceeds file length; returning through line ")
                append(lineCount)
                append(" because the file has only ")
                append(lineCount)
                append(" lines.")
            }
        }

        override fun execute(project: Project): ReadFileResult {
            if (filePath.isBlank()) {
                return ReadFileResult(
                    "",
                    "",
                    "filePath is required",
                )
            }

            val basePath =
                PathUtils.projectRootPath(project)
                    ?: return ReadFileResult(
                        "",
                        "",
                        "Project base path not found.",
                    )
            val resolved =
                try {
                    PathUtils.resolveWithinProject(project, filePath)
                } catch (e: IllegalArgumentException) {
                    return ReadFileResult(
                        "",
                        "",
                        e.message ?: "Invalid path",
                    )
                }
            val relToBase =
                try {
                    PathUtils.relativizeToProject(basePath, resolved)
                } catch (_: Throwable) {
                    resolved.toString()
                }

            return try {
                ApplicationManager.getApplication().runReadAction<ReadFileResult> {
                    val virtualFile =
                        PathUtils.resolveVirtualFileWithinProject(project, filePath)
                            ?: return@runReadAction ReadFileResult(
                                "",
                                "",
                                "File not found.",
                            )
                    if (!virtualFile.isFile) {
                        return@runReadAction ReadFileResult(
                            "",
                            "",
                            "It is not a file",
                        )
                    }

                    val doc = FileDocumentManager.getInstance().getDocument(virtualFile)
                    val rawContent =
                        try {
                            doc?.text ?: VfsUtilCore.loadText(virtualFile)
                        } catch (t: Throwable) {
                            return@runReadAction ReadFileResult(
                                "",
                                "",
                                "Unable to read file: ${t.message}",
                            )
                        }

                    val rangeErr = validateRange(fromLine, toLine)
                    if (rangeErr != null) {
                        return@runReadAction ReadFileResult(
                            "",
                            "",
                            rangeErr,
                        )
                    }
                    val fileLineCount = rawContent.lines().size
                    val fileRangeErr = validateRangeAgainstFile(fromLine, toLine, fileLineCount)
                    if (fileRangeErr != null) {
                        return@runReadAction ReadFileResult(
                            "",
                            "",
                            fileRangeErr,
                        )
                    }
                    val rangeClampWarning = buildRangeClampWarning(fromLine, toLine, fileLineCount).orEmpty()

                    val currentCtx =
                        try {
                            CurrentFileContextProvider(project).getCurrent()
                        } catch (_: Throwable) {
                            null
                        }
                    val isCurrentTarget = currentCtx?.filePathRelative?.equals(relToBase, ignoreCase = false) == true

                    val lines = rawContent.lines()
                    val totalFileLines = lines.size

                    // Step 1: explicit slice by line range, if requested (takes precedence)
                    var finalContent = rawContent
                    var firstLineNumber = 1
                    var requestedFromLineNormalized: Int? = null
                    var requestedToLineNormalized: Int? = null
                    var requestedEffectiveEndLine: Int? = null
                    val explicitRangeRequested = (fromLine != null || toLine != null)
                    if (explicitRangeRequested) {
                        val start1 = fromLine ?: 1
                        val end1 = toLine
                        requestedFromLineNormalized = start1
                        requestedToLineNormalized = end1
                        requestedEffectiveEndLine = end1 ?: totalFileLines

                        if (lines.isEmpty()) {
                            finalContent = ""
                            firstLineNumber = start1
                        } else {
                            val (start0, end0) =
                                clampWindow(
                                    start = start1 - 1,
                                    end = end1?.minus(1) ?: lines.lastIndex,
                                    lineCount = lines.size,
                                )
                            finalContent = lines.subList(start0, end0 + 1).joinToString("\n")
                            firstLineNumber = start0 + 1
                        }
                    }

                    // Step 2: truncate if too large
                    var truncated = false
                    val isTooLarge = finalContent.length > maxChars
                    if (isTooLarge) {
                        val lc = finalContent.lines().size
                        when (strategy.lowercase()) {
                            "head" -> {
                                val (slice, start0) = headByLines(finalContent, maxChars)
                                finalContent = slice
                                truncated = true
                                firstLineNumber += start0
                            }

                            "tail" -> {
                                val (slice, start0) = tailByLines(finalContent, maxChars)
                                finalContent = slice
                                truncated = true
                                firstLineNumber = (firstLineNumber + start0).coerceAtLeast(1)
                            }

                            else -> { // window
                                // Windowing is only meaningful for the current file when no explicit range was requested.
                                if (!explicitRangeRequested && preferWindowIfCurrentFile && isCurrentTarget) {
                                    val cStart = currentCtx.selectionStartLine
                                    val cEnd = currentCtx.selectionEndLine
                                    val caret = currentCtx.caretLine

                                    val startLine0: Int
                                    val endLine0: Int
                                    if (cStart != null && cEnd != null) {
                                        startLine0 = ((cStart - 1) - windowRadiusLines).coerceAtLeast(0)
                                        endLine0 = ((cEnd - 1) + windowRadiusLines).coerceAtMost(lc - 1)
                                    } else if (caret != null) {
                                        startLine0 = ((caret - 1) - windowRadiusLines).coerceAtLeast(0)
                                        endLine0 = ((caret - 1) + windowRadiusLines).coerceAtMost(lc - 1)
                                    } else {
                                        val (slice, start0) = headByLines(finalContent, maxChars)
                                        finalContent = slice
                                        truncated = true
                                        firstLineNumber += start0
                                        startLine0 = 0
                                        endLine0 = -1
                                    }

                                    if (startLine0 <= endLine0) {
                                        finalContent = sliceByLines(finalContent, startLine0, endLine0)
                                        truncated = true
                                        firstLineNumber += startLine0
                                    }
                                } else {
                                    val (slice, start0) = headByLines(finalContent, maxChars)
                                    finalContent = slice
                                    truncated = true
                                    firstLineNumber += start0
                                }
                            }
                        }
                    }

                    val (format, content) =
                        if (includeLineNumbers) {
                            "00001 line_content" to
                                withLineNumbers(
                                    finalContent,
                                    firstLineNumber,
                                )
                        } else {
                            "plain" to finalContent
                        }

                    val actualLineCount = if (finalContent.isEmpty()) 0 else finalContent.lines().size
                    val actualFromLine = if (actualLineCount > 0) firstLineNumber else null
                    val actualToLine = if (actualLineCount > 0) (firstLineNumber + actualLineCount - 1) else null
                    val hasMoreContent =
                        when {
                            actualToLine == null -> false
                            explicitRangeRequested -> actualToLine < (requestedEffectiveEndLine ?: actualToLine)
                            else -> actualToLine < totalFileLines
                        }
                    val truncationWarning =
                        if (truncated && actualFromLine != null && actualToLine != null) {
                            buildString {
                                append("Returned lines ")
                                append(actualFromLine)
                                append("..")
                                append(actualToLine)
                                append(" only; additional content was omitted due to maxChars/strategy.")
                            }
                        } else {
                            ""
                        }
                    val combinedWarning =
                        listOf(rangeClampWarning, truncationWarning)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")

                    val hash = sha256Normalized(rawContent)
                    QDLog.debug(logger) {
                        "Read file content: $relToBase, lineNumbers=$includeLineNumbers, truncated=$truncated, " +
                            "requestedFromLine=${requestedFromLineNormalized ?: ""} " +
                            "requestedToLine=${requestedToLineNormalized ?: ""} " +
                            "actualFromLine=${actualFromLine ?: ""} actualToLine=${actualToLine ?: ""} " +
                            "totalFileLines=$totalFileLines"
                    }

                    ReadFileResult(
                        format = format,
                        content = content,
                        error = "",
                        warning = combinedWarning,
                        requestedFromLine = requestedFromLineNormalized,
                        requestedToLine = requestedToLineNormalized,
                        actualFromLine = actualFromLine,
                        actualToLine = actualToLine,
                        totalFileLines = totalFileLines,
                        truncated = truncated,
                        hasMoreContent = hasMoreContent,
                        fileHashSha256 = hash,
                    )
                }
            } catch (e: Throwable) {
                QDLog.warn(logger, { "Failed to read file $relToBase" }, e)
                val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                ReadFileResult(error = "Failed to read file $relToBase: $message", format = "", content = "")
            }
        }
    }
