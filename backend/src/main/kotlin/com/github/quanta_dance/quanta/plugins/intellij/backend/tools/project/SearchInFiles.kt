// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.project

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.models.SearchInFilesResult
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressEvent
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressKind
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

/**
 * Backend search tool that wraps IntelliJ project file scanning and returns AI-friendly summaries.
 *
 * It is one of the primary exploration tools for agents because it can search broadly while still
 * capping result volume and producing a concise model summary.
 */
@JsonClassDescription(
    "Search for a text query across project files using IDE Find-in-Files (regex supported). " +
            "Returns concise matches and a modelSummary for AI context.",
)
class SearchInFiles : ToolInterface<SearchInFilesResult> {
    override val canBeParallel: Boolean = true

    @field:JsonPropertyDescription("Text to search for in project files (regex supported). Use a|b|c for OR.")
    var query: String? = null

    @field:JsonPropertyDescription("Maximum number of matches to return (soft limit). Hard limit is 50.")
    var maxResults: Int = 10

    @field:JsonPropertyDescription(
        "Optional list of file extensions to include (e.g., ['kt','java','txt']). " +
                "To search across all extensions, omit this field or pass ['*'].",
    )
    var includeExtensions: List<String>? = null

    @field:JsonPropertyDescription("Optional list of path segments to exclude (e.g., ['.git','build','out']).")
    var excludePathSegments: List<String>? = null

    @field:JsonPropertyDescription("Number of top files to summarize for the model context. Default 3.")
    var topForModel: Int = 3

    private val hardResultLimit = 50
    private val maxSnippetLength = 240
    private val maxTotalChars = 20_000
    private val maxFileCharsToScan = 1_000_000

    private data class FileMatch(
        val file: VirtualFile,
        val offsets: List<Int>,
        val text: String,
    )

    override fun execute(project: Project): SearchInFilesResult {
        val q = query?.trim() ?: ""
        if (q.isEmpty()) return SearchInFilesResult(emptyList(), modelSummary = null)

        try {
            project.getService(ToolProgressService::class.java).publish(
                ToolProgressEvent("Search in Files", ToolProgressKind.START, "Query: $q"),
            )
        } catch (_: Throwable) {
        }

        val searchRegex =
            try {
                val meta = setOf('.', '^', '$', '*', '+', '?', '{', '}', '[', ']', '(', ')', '|', '\\')
                val hasMeta = q.any { meta.contains(it) }
                if (!hasMeta) {
                    null
                } else {
                    try {
                        Regex(q, setOf(RegexOption.IGNORE_CASE))
                    } catch (e: Throwable) {
                        val msg = e.message ?: "Invalid regular expression"
                        return SearchInFilesResult(emptyList(), modelSummary = "Invalid regular expression: $msg")
                    }
                }
            } catch (_: Throwable) {
                null
            }

        val softLimit = maxResults.coerceAtLeast(1)
        val resultLimit = softLimit.coerceAtMost(hardResultLimit)

        val rawInclude = includeExtensions?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
        val includeExts =
            when {
                rawInclude.isNullOrEmpty() -> null
                rawInclude.size == 1 && rawInclude.first() == "*" -> null
                else -> rawInclude.toSet()
            }
        val excludeSegments = excludePathSegments ?: listOf(".git", "build", "out", "node_modules")

        val fileMatches = mutableListOf<FileMatch>()
        val totalChars = AtomicInteger(0)

        try {
            val fileIndex = ProjectFileIndex.getInstance(project)
            fileIndex.iterateContent { file ->
                if (fileMatches.sumOf { it.offsets.size } >= resultLimit || totalChars.get() >= maxTotalChars) {
                    return@iterateContent false
                }
                val text = readSearchableText(project, file, includeExts, excludeSegments) ?: return@iterateContent true
                val offsets = findMatchOffsets(text, q, searchRegex, resultLimit)
                if (offsets.isNotEmpty()) {
                    fileMatches += FileMatch(file, offsets, text)
                    totalChars.addAndGet(offsets.size * maxSnippetLength.coerceAtMost(100))
                }
                true
            }
        } catch (e: Throwable) {
            return SearchInFilesResult(emptyList(), modelSummary = "Search failed: ${e.message}")
        }

        val ranked = fileMatches.sortedByDescending { it.offsets.size }
        val basePathPath = PathUtils.projectRootPath(project)?.let { Paths.get(it) }
        val flatResults = mutableListOf<SearchInFilesResult.Match>()
        val modelSummary = StringBuilder()

        for (entry in ranked) {
            val rel = relativize(basePathPath, entry.file)
            for (off in entry.offsets) {
                if (flatResults.size >= resultLimit || totalChars.get() >= maxTotalChars) break
                val lineNumber = lineNumberAt(entry.text, off)
                val snippet = snippetAt(entry.text, off)
                flatResults += SearchInFilesResult.Match(rel, lineNumber, snippet)
                totalChars.addAndGet(snippet.length)
            }
            if (flatResults.size >= resultLimit || totalChars.get() >= maxTotalChars) break
        }

        val topN = topForModel.coerceAtLeast(1).coerceAtMost(10)
        modelSummary.append("Project context for query '$q' (top $topN files):\n")
        ranked.take(topN).forEach { entry ->
            val rel = relativize(basePathPath, entry.file)
            val firstOff = entry.offsets.firstOrNull() ?: return@forEach
            val lineNumber = lineNumberAt(entry.text, firstOff)
            val snippet = snippetAt(entry.text, firstOff)
            modelSummary.append("- $rel (matches=${entry.offsets.size}) line $lineNumber: $snippet\n")
        }

        return SearchInFilesResult(flatResults.take(resultLimit), modelSummary.toString())
    }

    private fun readSearchableText(
        project: Project,
        file: VirtualFile,
        includeExts: Set<String>?,
        excludeSegments: List<String>,
    ): String? =
        ApplicationManager.getApplication().runReadAction<String?> {
            if (!file.isValid || file.isDirectory) return@runReadAction null
            if (file.fileType.isBinary) return@runReadAction null
            if (file.length > maxFileCharsToScan) return@runReadAction null

            val segs = file.path.lowercase().split('/', '\\')
            if (excludeSegments.any { seg -> segs.contains(seg.lowercase()) }) return@runReadAction null

            if (includeExts != null) {
                val ext = file.extension?.lowercase() ?: return@runReadAction null
                if (!includeExts.contains(ext)) return@runReadAction null
            }

            val psiText = runCatching { PsiManager.getInstance(project).findFile(file)?.text }.getOrNull()
            if (!psiText.isNullOrEmpty()) return@runReadAction psiText

            runCatching { VfsUtilCore.loadText(file) }.getOrNull()
        }

    private fun findMatchOffsets(
        text: String,
        query: String,
        searchRegex: Regex?,
        resultLimit: Int,
    ): List<Int> {
        if (text.isEmpty()) return emptyList()
        return if (searchRegex != null) {
            searchRegex
                .findAll(text)
                .map { it.range.first }
                .take(resultLimit)
                .toList()
        } else {
            buildList {
                var startIndex = 0
                while (size < resultLimit) {
                    val matchIndex = text.indexOf(query, startIndex = startIndex, ignoreCase = true)
                    if (matchIndex < 0) break
                    add(matchIndex)
                    startIndex = (matchIndex + 1).coerceAtMost(text.length)
                }
            }
        }
    }

    private fun relativize(basePathPath: java.nio.file.Path?, file: VirtualFile): String =
        basePathPath?.let { bp ->
            try {
                bp.relativize(Paths.get(file.path)).toString()
            } catch (_: Throwable) {
                file.path
            }
        } ?: file.path

    private fun lineNumberAt(text: String, offset: Int): Int {
        val safeOff = offset.coerceIn(0, text.length)
        return text.substring(0, safeOff).count { it == '\n' } + 1
    }

    private fun snippetAt(text: String, offset: Int): String {
        val safeOff = offset.coerceIn(0, text.length)
        val start = (text.lastIndexOf('\n', safeOff).takeIf { it >= 0 } ?: (safeOff - 40)).coerceAtLeast(0)
        val end =
            (text.indexOf('\n', safeOff).takeIf { it >= 0 } ?: (safeOff + maxSnippetLength)).coerceAtMost(text.length)
        val snippet =
            runCatching {
                text
                    .substring(
                        start.coerceAtLeast(0).coerceAtMost(text.length),
                        end.coerceAtLeast(0).coerceAtMost(text.length),
                    ).replace('\n', ' ')
            }.getOrDefault("")
        return if (snippet.length > maxSnippetLength) snippet.take(maxSnippetLength) + "..." else snippet
    }
}
