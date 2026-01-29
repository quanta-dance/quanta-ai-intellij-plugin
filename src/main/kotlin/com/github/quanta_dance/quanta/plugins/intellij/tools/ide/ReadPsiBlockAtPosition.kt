// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.project.CurrentFileContextProvider
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import java.io.File

@JsonClassDescription(
    "Read the enclosing PSI block (function/method/class/field/object) " +
        "at a position in a file and return structured metadata and text.",
)
class ReadPsiBlockAtPosition : ToolInterface<Map<String, Any?>> {
    @field:JsonPropertyDescription("Relative to the project root path. If omitted, uses current editor file.")
    var filePath: String? = null

    @field:JsonPropertyDescription("1-based line number (optional). If both line and column are set, offset is computed from them.")
    var line: Int? = null

    @field:JsonPropertyDescription("0-based column offset on the line (optional).")
    var column: Int? = null

    @field:JsonPropertyDescription("Absolute offset in the file (optional). If provided, takes precedence over line/column.")
    var offset: Int? = null

    @field:JsonPropertyDescription("Scope preference: auto|function|method|class|field|object. Default: auto.")
    var scope: String = "auto"

    @field:JsonPropertyDescription("Include prefixed line numbers in the returned text. Default: false")
    var includeLineNumbers: Boolean = false

    @field:JsonPropertyDescription("Maximum characters to return (guard). Default 6000")
    var maxChars: Int = 6000

    private val log = Logger.getInstance(ReadPsiBlockAtPosition::class.java)

    override fun execute(project: Project): Map<String, Any?> {
        val base = project.basePath ?: return err("Project base path not found.")
        val ctx =
            try {
                CurrentFileContextProvider(project).getCurrent()
            } catch (_: Throwable) {
                null
            }

        val rel =
            (filePath?.trim().takeUnless { it.isNullOrEmpty() } ?: ctx?.filePathRelative)
                ?: return err("filePath is required and no current editor file is available")

        val io =
            try {
                PathUtils.resolveWithinProject(base, rel).toFile()
            } catch (e: IllegalArgumentException) {
                project.service<ToolWindowService>()
                    .addToolingMessage("ReadPsiBlockAtPosition - invalid path", e.message ?: "Invalid path")
                return err(e.message ?: "Invalid path")
            }
        val vFile =
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io) ?: run {
                VfsUtil.markDirtyAndRefresh(true, true, true, File(base))
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)
            } ?: return err("File not found: $rel")

        return ApplicationManager.getApplication().runReadAction<Map<String, Any?>> {
            val psiFile =
                PsiManager.getInstance(project).findFile(vFile)
                    ?: return@runReadAction err("PSI file not found: $rel")
            val doc =
                FileDocumentManager.getInstance().getDocument(vFile)
                    ?: return@runReadAction err("Document not found for: $rel")

            val psiDocMgr = PsiDocumentManager.getInstance(project)
            if (!psiDocMgr.isCommitted(doc)) {
                ApplicationManager.getApplication().runWriteAction {
                    try {
                        psiDocMgr.commitDocument(doc)
                    } catch (_: Throwable) {
                    }
                }
            }

            val effectiveOffset =
                computeOffset(doc.text, line, column, offset, ctx)
                    ?: return@runReadAction err("Need position: provide line/column or offset, or ensure caret is available")

            val leaf = psiFile.findElementAt(effectiveOffset)
            val chosen = chooseElementByScope(leaf, scope)
            if (chosen == null) {
                val window = windowAroundOffset(doc.text, effectiveOffset, maxChars)
                return@runReadAction mapOf(
                    "status" to "ok",
                    "file" to rel,
                    "elementKind" to "window",
                    "name" to null,
                    "offset" to effectiveOffset,
                    "startLine" to window.firstLine,
                    "endLine" to window.lastLine,
                    "text" to withOptionalLineNumbers(window.text, window.firstLine),
                )
            }
            val (element, kind) = chosen

            val range = element.textRange
            val startLine = doc.getLineNumber(range.startOffset) + 1
            val endLine = doc.getLineNumber(range.endOffset.coerceAtLeast(range.startOffset)) + 1
            val name = (element as? PsiNamedElement)?.name ?: element.javaClass.simpleName
            var text = element.text
            if (text.length > maxChars) text = text.take(maxChars)

            project.service<ToolWindowService>().addToolingMessage(
                "Read PSI block",
                "$rel:$startLine-$endLine ($kind $name)",
            )

            mapOf(
                "status" to "ok",
                "file" to rel,
                "elementKind" to kind,
                "name" to name,
                "offset" to effectiveOffset,
                "startLine" to startLine,
                "endLine" to endLine,
                "text" to withOptionalLineNumbers(text, startLine),
            )
        }
    }

    private fun err(msg: String): Map<String, Any?> = mapOf("status" to "error", "message" to msg)

    private fun withOptionalLineNumbers(
        text: String,
        startLine: Int,
    ): String {
        if (!includeLineNumbers) return text
        val base = startLine.coerceAtLeast(1)
        return text.lines().mapIndexed { i, line -> "%05d %s".format(base + i, line) }.joinToString("\n")
    }

    private data class Window(val text: String, val firstLine: Int, val lastLine: Int)

    private fun windowAroundOffset(
        raw: String,
        offset: Int,
        maxChars: Int,
    ): Window {
        val lines = raw.lines()
        val safeOffset = offset.coerceIn(0, raw.length)
        var lineIdx = 0
        var i = 0
        while (i < safeOffset) {
            if (raw[i] == '\n') lineIdx++
            i++
        }
        val radiusLines = 100
        val start = (lineIdx - radiusLines).coerceAtLeast(0)
        val end = (lineIdx + radiusLines).coerceAtMost(lines.lastIndex)
        var chunk = lines.subList(start, end + 1).joinToString("\n")
        if (chunk.length > maxChars) chunk = chunk.take(maxChars)
        return Window(chunk, start + 1, start + 1 + chunk.lines().size - 1)
    }

    private fun computeOffset(
        raw: String,
        line: Int?,
        column: Int?,
        offset: Int?,
        ctx: CurrentFileContextProvider.CurrentFileContext?,
    ): Int? {
        if (offset != null && offset >= 0) return offset
        val l = line
        val c = column
        if (l != null && c != null && l >= 1 && c >= 0) {
            var idx = 0
            var ln = 1
            while (idx < raw.length && ln < l) {
                if (raw[idx] == '\n') ln++
                idx++
            }
            return (idx + c).coerceAtMost(raw.length)
        }
        return ctx?.caretLine
    }

    private fun chooseElementByScope(
        leaf: PsiElement?,
        scope: String,
    ): Pair<PsiElement, String>? {
        if (leaf == null) return null
        val prefer = scope.lowercase()
        val ktFunction = tryLoadPsi("org.jetbrains.kotlin.psi.KtNamedFunction")
        val ktClass = tryLoadPsi("org.jetbrains.kotlin.psi.KtClass")
        val ktObject = tryLoadPsi("org.jetbrains.kotlin.psi.KtObjectDeclaration")
        val ktProperty = tryLoadPsi("org.jetbrains.kotlin.psi.KtProperty")
        val psiMethod = tryLoadPsi("com.intellij.psi.PsiMethod")
        val psiClass = tryLoadPsi("com.intellij.psi.PsiClass")
        val psiField = tryLoadPsi("com.intellij.psi.PsiField")

        fun parentOfType(target: Class<out PsiElement>): PsiElement? = PsiTreeUtil.getParentOfType(leaf, target, false)

        fun firstMatchAuto(): Pair<PsiElement, String>? {
            listOf(
                ktFunction to "function",
                psiMethod to "method",
                ktClass to "class",
                psiClass to "class",
                ktObject to "object",
                ktProperty to "field",
                psiField to "field",
            ).forEach { (cls, kind) -> if (cls != null) parentOfType(cls)?.let { return it to kind } }
            var p: PsiElement? = leaf
            while (p != null && p !is PsiNamedElement) p = p.parent
            if (p is PsiNamedElement) return p to (p.javaClass.simpleName)
            var q: PsiElement? = leaf.parent
            while (q != null && q.textLength < 1) q = q.parent
            return if (q != null) q to q.javaClass.simpleName else null
        }

        return when (prefer) {
            "function" -> ktFunction?.let { parentOfType(it) }?.let { it to "function" } ?: firstMatchAuto()
            "method" -> psiMethod?.let { parentOfType(it) }?.let { it to "method" } ?: firstMatchAuto()
            "class" -> (ktClass?.let { parentOfType(it) } ?: psiClass?.let { parentOfType(it) })?.let { it to "class" } ?: firstMatchAuto()
            "field" ->
                (
                    ktProperty?.let {
                        parentOfType(
                            it,
                        )
                    } ?: psiField?.let { parentOfType(it) }
                )?.let { it to "field" } ?: firstMatchAuto()

            "object" -> ktObject?.let { parentOfType(it) }?.let { it to "object" } ?: firstMatchAuto()
            else -> firstMatchAuto()
        }
    }

    private fun tryLoadPsi(name: String): Class<out PsiElement>? =
        try {
            @Suppress("UNCHECKED_CAST")
            Class.forName(name)
                as Class<out PsiElement>
        } catch (_: Throwable) {
            null
        }
}
