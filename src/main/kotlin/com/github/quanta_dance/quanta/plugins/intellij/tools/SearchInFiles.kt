package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.models.SearchInFilesResult
import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.Processor
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@JsonClassDescription("Search for a text query across project files using IDE Find-in-Files (regex supported). Returns concise matches and a modelSummary for AI context.")
class SearchInFiles : ToolInterface<SearchInFilesResult> {

    @JsonPropertyDescription("Text to search for in project files (regex supported). Use a|b|c for OR.")
    var query: String? = null

    @JsonPropertyDescription("Maximum number of matches to return (soft limit). Hard limit is 50.")
    var maxResults: Int = 10

    @JsonPropertyDescription("Optional list of file extensions to include (e.g., ['kt','java','txt']). To search across all extensions, omit this field or pass ['*'].")
    var includeExtensions: List<String>? = null

    @JsonPropertyDescription("Optional list of path segments to exclude (e.g., ['.git','build','out']).")
    var excludePathSegments: List<String>? = null

    @JsonPropertyDescription("Number of top files to summarize for the model context. Default 3.")
    var topForModel: Int = 3

    private val hardResultLimit = 50
    private val maxSnippetLength = 240
    private val maxTotalChars = 20_000

    override fun execute(project: Project): SearchInFilesResult {
        val q = query?.trim() ?: ""
        if (q.isEmpty()) return SearchInFilesResult(emptyList(), modelSummary = null)

        try { project.getService(ToolWindowService::class.java).addToolingMessage("Search in Files", "Query: $q") } catch (_: Throwable) {}

        // Decide whether to run as regex or literal in a single Find-in-Project invocation.
        // Strategy:
        // 1) If q is an invalid regex, return a friendly message and no results.
        // 2) If q contains no regex meta-characters, treat as literal (users often expect this).
        // 3) Otherwise treat as regex.
        val treatAsRegex = try {
            // quick heuristic for meta-characters commonly used in regexes
            val meta = setOf('.', '^', '$', '*', '+', '?', '{', '}', '[', ']', '(', ')', '|', '\\')
            val hasMeta = q.any { meta.contains(it) }
            if (!hasMeta) {
                try { project.getService(ToolWindowService::class.java).addToolingMessage("Search in Files", "Query appears literal; running literal search") } catch (_: Throwable) {}
                false
            } else {
                // validate regex
                try { Regex(q); try { project.getService(ToolWindowService::class.java).addToolingMessage("Search in Files", "Query appears regex; running regex search") } catch (_: Throwable) {} ; true } catch (e: Throwable) {
                    // Return a friendly summary indicating the regex is invalid (test expects this)
                    val msg = e.message ?: "Invalid regular expression"
                    try { project.getService(ToolWindowService::class.java).addToolingMessage("Search in Files - invalid regex, using literal", msg) } catch (_: Throwable) {}
                    return SearchInFilesResult(emptyList(), modelSummary = "Invalid regular expression: $msg")
                }
            }
        } catch (e: Throwable) {
            try { project.getService(ToolWindowService::class.java).addToolingMessage("Search in Files - detection failed", e.message ?: "detection error") } catch (_: Throwable) {}
            false
        }

        val softLimit = maxResults.coerceAtLeast(1)
        val resultLimit = softLimit.coerceAtMost(hardResultLimit)

        val rawInclude = includeExtensions?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
        val includeExts = when {
            rawInclude.isNullOrEmpty() -> null
            rawInclude.size == 1 && rawInclude.first() == "*" -> null
            else -> rawInclude.toSet()
        }
        val excludeSegments = excludePathSegments ?: listOf(".git", "build", "out", "node_modules")

        // Obtain baseScope safely: projectScope may access project user data which fails on lightweight mocks.
        val baseScope: GlobalSearchScope = try {
            GlobalSearchScope.projectScope(project)
        } catch (e: Throwable) {
            // Fallback permissive scope for tests and unusual environments — filtering below will still apply
            object : GlobalSearchScope(project) {
                override fun contains(file: VirtualFile): Boolean = true
                override fun isSearchInModuleContent(aModule: com.intellij.openapi.module.Module) = true
                override fun isSearchInLibraries() = false
            }
        }

        val filteredScope = object : GlobalSearchScope(project) {
            override fun contains(file: VirtualFile): Boolean {
                if (!baseScope.contains(file)) return false
                val segs = file.path.lowercase().split('/', '\\')
                if (excludeSegments.any { seg -> segs.contains(seg.lowercase()) }) return false
                if (includeExts != null) {
                    val ext = file.extension?.lowercase()
                    if (ext == null || !includeExts.contains(ext)) return false
                }
                return true
            }

            override fun isSearchInModuleContent(aModule: com.intellij.openapi.module.Module) = true
            override fun isSearchInLibraries() = false
        }

        // Use a concurrent map to avoid ConcurrentModificationException when find API updates entries on other threads
        val fileOffsets = ConcurrentHashMap<VirtualFile, MutableList<Int>>()
        val totalChars = AtomicInteger(0)

        // Build FindModel outside read action
        val model = FindModel().apply {
            stringToFind = q
            isRegularExpressions = treatAsRegex
            isCaseSensitive = false
            isWholeWordsOnly = false
            customScope = (filteredScope as SearchScope)
            customScopeName = if (treatAsRegex) "Project Files (regex)" else "Project Files (literal)"
            if (includeExts != null) {
                fileFilter = includeExts.joinToString(";") { "*.${it}" }
            }
        }

        val usagePresentation = UsageViewPresentation()
        val presentation = FindInProjectUtil.setupProcessPresentation(true, usagePresentation)

        val processor = Processor<UsageInfo> { usage ->
            val vFile = usage.virtualFile ?: return@Processor true
            if (!filteredScope.contains(vFile)) return@Processor true
            val off = usage.navigationOffset
            if (off < 0) return@Processor true
            // guard offset by file length if possible
            val fileLen = try { vFile.length.toInt() } catch (_: Throwable) { Int.MAX_VALUE }
            if (off > fileLen) return@Processor true
            val list = fileOffsets.computeIfAbsent(vFile) { Collections.synchronizedList(mutableListOf()) }
            list.add(off)
            // approximate contribution to totalChars by adding a snippet length cap
            val approxAdd = maxSnippetLength.coerceAtMost(100)
            val newTotal = totalChars.addAndGet(approxAdd)
            newTotal < maxTotalChars && fileOffsets.values.sumOf { it.size } < resultLimit
        }

        // Protection around IDE find - capture exceptions and return user-friendly result
        try {
            // Must NOT be inside read action
            FindInProjectUtil.findUsages(model, project, processor, presentation)
            try { project.getService(ToolWindowService::class.java).addToolingMessage("Search in Files", "Completed findUsages (mode=${if (treatAsRegex) "regex" else "literal"}): files=${fileOffsets.size}") } catch (_: Throwable) {}
        } catch (e: Throwable) {
            try { project.getService(ToolWindowService::class.java).addToolingMessage("Search in Files - failed", e.message ?: "findUsages failed") } catch (_: Throwable) {}
            return SearchInFilesResult(emptyList(), modelSummary = "Search failed: ${e.message}")
        }

        val flatResults = mutableListOf<SearchInFilesResult.Match>()
        val modelSummary = StringBuilder()
        val basePathPath = project.basePath?.let { Paths.get(it) }
        ApplicationManager.getApplication().runReadAction {
            val psiManager = PsiManager.getInstance(project)
            val docManager = PsiDocumentManager.getInstance(project)

            // snapshot entries to avoid concurrent modification while iterating
            val ranked = fileOffsets.entries.toList().sortedByDescending { it.value.size }

            for ((vf, offsets) in ranked) {
                val psiFile = psiManager.findFile(vf)
                val text = psiFile?.text ?: ""
                val doc = psiFile?.let { docManager.getDocument(it) }
                val rel = basePathPath?.let { bp ->
                    try { bp.relativize(Paths.get(vf.path)).toString() } catch (_: Throwable) { vf.path }
                } ?: vf.path
                for (off in offsets) {
                    if (flatResults.size >= resultLimit || totalChars.get() >= maxTotalChars) break
                    val safeOff = off.coerceIn(0, text.length)
                    val lineNumber = try {
                        doc?.getLineNumber(safeOff)?.plus(1) ?: (text.substring(0, safeOff).count { it == '\n' } + 1)
                    } catch (e: Throwable) {
                        try { text.substring(0, safeOff).count { it == '\n' } + 1 } catch (_: Throwable) { 1 }
                    }
                    val start = (text.lastIndexOf('\n', safeOff).takeIf { it >= 0 } ?: (safeOff - 40)).coerceAtLeast(0)
                    val end = (text.indexOf('\n', safeOff).takeIf { it >= 0 } ?: (safeOff + maxSnippetLength)).coerceAtMost(text.length)
                    var snippet = try { text.substring(start.coerceAtLeast(0).coerceAtMost(text.length), end.coerceAtLeast(0).coerceAtMost(text.length)).replace('\n', ' ') } catch (_: Throwable) { "" }
                    if (snippet.length > maxSnippetLength) snippet = snippet.take(maxSnippetLength) + "..."
                    flatResults.add(SearchInFilesResult.Match(rel, lineNumber, snippet))
                    totalChars.addAndGet(snippet.length)
                }
                if (flatResults.size >= resultLimit || totalChars.get() >= maxTotalChars) break
            }

            val topN = topForModel.coerceAtLeast(1).coerceAtMost(10)
            modelSummary.append("Project context for query '$q' (top $topN files):\n")
            ranked.take(topN).forEach { (vf, offs) ->
                val psiFile = psiManager.findFile(vf)
                val text = psiFile?.text ?: return@forEach
                val firstOff = offs.first()
                val safeOff = firstOff.coerceIn(0, text.length)
                val lineNumber = text.substring(0, safeOff).count { it == '\n' } + 1
                val start = (text.lastIndexOf('\n', safeOff).takeIf { it >= 0 } ?: (safeOff - 40)).coerceAtLeast(0)
                val end = (text.indexOf('\n', safeOff).takeIf { it >= 0 } ?: (safeOff + maxSnippetLength)).coerceAtMost(text.length)
                var snippet = try { text.substring(start.coerceAtLeast(0).coerceAtMost(text.length), end.coerceAtLeast(0).coerceAtMost(text.length)).replace('\n', ' ') } catch (_: Throwable) { "" }
                if (snippet.length > maxSnippetLength) snippet = snippet.take(maxSnippetLength) + "..."
                val rel = basePathPath?.let { bp ->
                    try { bp.relativize(Paths.get(vf.path)).toString() } catch (_: Throwable) { vf.path }
                } ?: vf.path
                modelSummary.append("- ${rel} (matches=${offs.size}) line $lineNumber: $snippet\n")
            }
        }

        try {
            val display = StringBuilder()
            display.append("Searched project: query='$q' files=${fileOffsets.size} results=${flatResults.size} (cap=$resultLimit)\n")
            val grouped = flatResults.groupBy { it.path }
            val preview = grouped.entries.take(5)
            preview.forEach { (path, matches) ->
                val first = matches.first()
                display.append("$path : line ${first.line} : ${first.snippet} (matches=${matches.size})\n")
            }
            if (grouped.size > 5) display.append("...+${grouped.size - 5} more files\n")
            project.getService(ToolWindowService::class.java).addToolingMessage("Search results", display.toString())
        } catch (e: Throwable) {
            try { project.getService(ToolWindowService::class.java).addToolingMessage("Search results - failed to prepare display", e.message ?: "display failed") } catch (_: Throwable) {}
        }

        return SearchInFilesResult(flatResults.take(resultLimit), modelSummary.toString())
    }
}
