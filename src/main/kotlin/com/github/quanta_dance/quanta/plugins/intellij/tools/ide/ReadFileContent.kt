// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.project.CurrentFileContextProvider
import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.github.quanta_dance.quanta.plugins.intellij.tools.models.ReadFileResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.isFile
import java.security.MessageDigest

@JsonClassDescription(
    "Read the content of requested file. Supports optional truncation and windowed reading around caret/selection for the current file.",
)
class ReadFileContent : ToolInterface<ReadFileResult> {
    @field:JsonPropertyDescription("Relative to the project root path to the requested file.")
    var filePath: String? = null

    @field:JsonPropertyDescription("If true, returns content with prefixed line numbers. Default false.")
    var includeLineNumbers: Boolean = false

    @field:JsonPropertyDescription("Maximum characters to return; if exceeded, tool truncates per strategy. Default 6000.")
    var maxChars: Int = 6_000

    @field:JsonPropertyDescription("Preferred truncation strategy when file exceeds maxChars: head | tail | window. Default window.")
    var strategy: String = "window"

    @field:JsonPropertyDescription(
        "If true, and the file is the current editor file with caret/selection, " +
            "return a window around caret/selection when truncating.",
    )
    var preferWindowIfCurrentFile: Boolean = true

    @field:JsonPropertyDescription("Window radius in lines (before and after caret or selection) when strategy=window. Default 300.")
    var windowRadiusLines: Int = 300

    companion object {
        private val logger = Logger.getInstance(ReadFileContent::class.java)
    }

    private fun addMsg(
        project: Project,
        title: String,
        msg: String,
    ) {
        try {
            project.service<ToolWindowService>().addToolingMessage(title, msg)
        } catch (_: Throwable) {
        }
    }

    private fun withLineNumbers(
        fileContent: String,
        startLineNumber: Int = 1,
    ): String {
        val base = startLineNumber.coerceAtLeast(1)
        return fileContent.lines().mapIndexed { index, line -> "%05d %s".format(base + index, line) }.joinToString("\n")
    }

    private fun clampWindow(
        start: Int,
        end: Int,
        lineCount: Int,
    ): Pair<Int, Int> {
        val s = start.coerceAtLeast(0)
        val e = end.coerceAtMost(lineCount - 1).coerceAtLeast(s)
        return Pair(s, e)
    }

    private fun sliceByLines(
        raw: String,
        fromLine0: Int,
        toLine0: Int,
    ): String {
        val lines = raw.lines()
        val (s, e) = clampWindow(fromLine0, toLine0, lines.size)
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

    private fun sha256Normalized(raw: String): String {
        val norm = raw.replace("\r\n", "\n").replace("\r", "\n")
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(norm.toByteArray())
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    override fun execute(project: Project): ReadFileResult {
        val basePath = project.basePath ?: return ReadFileResult("", "", "Project base path not found.")
        val resolved =
            try {
                PathUtils.resolveWithinProject(basePath, filePath)
            } catch (e: IllegalArgumentException) {
                addMsg(project, "Read file content - rejected", e.message ?: "Invalid path")
                return ReadFileResult(
                    "",
                    "",
                    e.message ?: "Invalid path",
                )
            }
        val relToBase = PathUtils.relativizeToProject(basePath, resolved)
        addMsg(project, "Read file content", relToBase)

        return ApplicationManager.getApplication().runReadAction<ReadFileResult> {
            val baseDir = project.baseDir
            val virtualFile =
                baseDir?.findFileByRelativePath(relToBase) ?: return@runReadAction ReadFileResult(
                    "",
                    "",
                    "File not found.",
                )
            if (!virtualFile.isFile) return@runReadAction ReadFileResult("", "", "It is not a file")

            val doc = FileDocumentManager.getInstance().getDocument(virtualFile)
            val rawContent =
                try {
                    doc?.text ?: VfsUtilCore.loadText(virtualFile)
                } catch (t: Throwable) {
                    return@runReadAction ReadFileResult("", "", "Unable to read file: ${t.message}")
                }

            val isTooLarge = rawContent.length > maxChars
            val currentCtx =
                try {
                    CurrentFileContextProvider(project).getCurrent()
                } catch (_: Throwable) {
                    null
                }
            val isCurrentTarget = currentCtx?.filePathRelative?.equals(relToBase, ignoreCase = false) == true

            var finalContent = rawContent
            var truncated = false
            var firstLineNumber = 1
            if (isTooLarge) {
                val lines = rawContent.lines()
                val lc = lines.size
                when (strategy.lowercase()) {
                    "head" -> {
                        val (slice, start0) = headByLines(rawContent, maxChars)
                        finalContent = slice
                        truncated =
                            true
                        firstLineNumber = start0 + 1
                    }

                    "tail" -> {
                        val (slice, start0) = tailByLines(rawContent, maxChars)
                        finalContent = slice
                        truncated =
                            true
                        firstLineNumber = start0 + 1
                    }

                    else -> { // window
                        if (preferWindowIfCurrentFile && isCurrentTarget) {
                            val cStart = currentCtx?.selectionStartLine
                            val cEnd = currentCtx?.selectionEndLine
                            val caret = currentCtx?.caretLine
                            val startLine0: Int
                            val endLine0: Int
                            if (cStart != null && cEnd != null) {
                                startLine0 = ((cStart - 1) - windowRadiusLines).coerceAtLeast(0)
                                endLine0 = ((cEnd - 1) + windowRadiusLines).coerceAtMost(lc - 1)
                            } else if (caret != null) {
                                startLine0 = ((caret - 1) - windowRadiusLines).coerceAtLeast(0)
                                endLine0 = ((caret - 1) + windowRadiusLines).coerceAtMost(lc - 1)
                            } else {
                                val (slice, start0) = headByLines(rawContent, maxChars)
                                finalContent =
                                    slice
                                truncated = true
                                firstLineNumber = start0 + 1
                                startLine0 = 0
                                endLine0 = -1
                            }
                            if (startLine0 <= endLine0) {
                                finalContent = sliceByLines(rawContent, startLine0, endLine0)
                                truncated =
                                    true
                                firstLineNumber = startLine0 + 1
                            }
                        } else {
                            val (slice, start0) = headByLines(rawContent, maxChars)
                            finalContent = slice
                            truncated =
                                true
                            firstLineNumber = start0 + 1
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
            val hash = sha256Normalized(rawContent)
            if (truncated) {
                addMsg(
                    project,
                    "Read file content - truncated",
                    "strategy=$strategy maxChars=$maxChars windowRadiusLines=$windowRadiusLines " +
                        "current=$isCurrentTarget startLine=$firstLineNumber",
                )
            } else {
                addMsg(project, "Read file content - success", "lineNumbers=$includeLineNumbers")
            }
            QDLog.debug(logger) {
                "Read file content: $relToBase, lineNumbers=$includeLineNumbers, " +
                    "truncated=$truncated, startLine=$firstLineNumber"
            }
            ReadFileResult(format, content, "", hash)
        }
    }
}
