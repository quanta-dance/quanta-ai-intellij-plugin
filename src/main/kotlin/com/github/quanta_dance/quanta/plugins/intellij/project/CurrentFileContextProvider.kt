// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.project

import com.github.quanta_dance.quanta.plugins.intellij.project.VersionUtil.computeVersion
import com.github.quanta_dance.quanta.plugins.intellij.tools.PathUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import java.nio.file.Paths

class CurrentFileContextProvider(private val project: Project) {
    data class CurrentFileContext(
        val projectBase: String,
        val filePathRelative: String,
        val version: Long,
        val caretLine: Int?,
        val caretColumn: Int?,
        val selectionStartLine: Int?,
        val selectionStartColumn: Int?,
        val selectionEndLine: Int?,
        val selectionEndColumn: Int?,
        val selectedText: String?,
    )

    fun getCurrent(): CurrentFileContext? {
        val basePath = project.basePath ?: return null
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val vf = editor.virtualFile ?: return null

        val rel =
            try {
                val filePath = Paths.get(vf.path).toAbsolutePath().normalize().toString()
                val relPath = Paths.get(basePath).toAbsolutePath().normalize().relativize(Paths.get(filePath)).toString()
                PathUtils.relativizeToProject(basePath, Paths.get(filePath)) // ensure forward slashes
            } catch (_: Throwable) {
                return null
            }

        val version =
            ApplicationManager.getApplication().runReadAction<Long> {
                val psi = PsiManager.getInstance(project).findFile(vf)
                val psiStamp = psi?.modificationStamp ?: 0L
                val docStamp = FileDocumentManager.getInstance().getDocument(vf)?.modificationStamp ?: 0L
                val vfsStamp = VersionUtil.safeVfsStamp(vf)
                computeVersion(psiStamp, docStamp, vfsStamp)
            }

        return ApplicationManager.getApplication().runReadAction<CurrentFileContext> {
            val caretModel = editor.caretModel
            val selectionModel = editor.selectionModel

            var caretLine: Int? = null
            var caretCol: Int? = null
            var selStartLine: Int? = null
            var selStartCol: Int? = null
            var selEndLine: Int? = null
            var selEndCol: Int? = null
            var selText: String? = null

            caretModel.currentCaret?.let { caret ->
                val pos = caret.logicalPosition
                caretLine = pos.line + 1
                caretCol = pos.column
            }
            if (selectionModel.hasSelection()) {
                selText = selectionModel.selectedText
                val startOffset = selectionModel.selectionStart
                val endOffset = selectionModel.selectionEnd
                val startPos = editor.offsetToLogicalPosition(startOffset)
                val endPos = editor.offsetToLogicalPosition(endOffset)
                selStartLine = startPos.line + 1
                selStartCol = startPos.column
                selEndLine = endPos.line + 1
                selEndCol = endPos.column - 1
            }

            CurrentFileContext(
                projectBase = basePath,
                filePathRelative = rel,
                version = version,
                caretLine = caretLine,
                caretColumn = caretCol,
                selectionStartLine = selStartLine,
                selectionStartColumn = selStartCol,
                selectionEndLine = selEndLine,
                selectionEndColumn = selEndCol,
                selectedText = selText,
            )
        }
    }
}
