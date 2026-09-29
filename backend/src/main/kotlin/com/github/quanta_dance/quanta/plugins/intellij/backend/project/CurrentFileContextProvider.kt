// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.project

import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide.FileHashUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import java.nio.file.Paths

/**
 * Provides the active editor context while caching the content hash until the selected file changes.
 *
 * Hashing an unchanged document on every agent turn is unnecessary and can be expensive for large files.
 * Caret and selection data remain live, while only the immutable hash is cached by file and modification stamp.
 */
@Service(Service.Level.PROJECT)
class CurrentFileContextProvider(
    private val project: Project,
) {
    @Volatile
    private var cachedHashKey: String? = null

    @Volatile
    private var cachedHash: String? = null

    private val hashLock = Any()

    data class CurrentFileContext(
        val projectBase: String,
        val filePathRelative: String,
        val fileHashSha256: String,
        val caretLine: Int?,
        val caretColumn: Int?,
        val selectionStartLine: Int?,
        val selectionStartColumn: Int?,
        val selectionEndLine: Int?,
        val selectionEndColumn: Int?,
        val selectedText: String?,
    )

    fun getCurrent(): CurrentFileContext? {
        val basePath = PathUtils.projectRootPath(project) ?: return null
        val fileEditorManager = FileEditorManager.getInstance(project)
        val editor = fileEditorManager.selectedTextEditor
        val vf =
            editor?.virtualFile
                ?: fileEditorManager.selectedFiles.firstOrNull()
                ?: return null

        val rel =
            try {
                val filePath =
                    Paths
                        .get(vf.path)
                        .toAbsolutePath()
                        .normalize()
                        .toString()
                Paths
                    .get(basePath)
                    .toAbsolutePath()
                    .normalize()
                    .relativize(Paths.get(filePath))
                    .toString()
                PathUtils.relativizeToProject(basePath, Paths.get(filePath))
            } catch (_: Throwable) {
                return null
            }

        return ApplicationManager.getApplication().runReadAction<CurrentFileContext> {
            val document = FileDocumentManager.getInstance().getDocument(vf)
            val modificationStamp = document?.modificationStamp ?: vf.modificationStamp
            val hashKey = "${vf.url}:$modificationStamp"
            val fileHashSha256 =
                synchronized(hashLock) {
                    if (cachedHashKey == hashKey) {
                        checkNotNull(cachedHash)
                    } else {
                        FileHashUtil
                            .sha256Normalized(
                                document?.text ?: vf.contentsToByteArray().toString(Charsets.UTF_8),
                            ).also { hash ->
                                cachedHashKey = hashKey
                                cachedHash = hash
                            }
                    }
                }

            var caretLine: Int? = null
            var caretCol: Int? = null
            var selStartLine: Int? = null
            var selStartCol: Int? = null
            var selEndLine: Int? = null
            var selEndCol: Int? = null
            var selText: String? = null

            if (editor != null && editor.virtualFile == vf) {
                val caretModel = editor.caretModel
                val selectionModel = editor.selectionModel

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
                    selEndCol = endPos.column
                }
            }

            CurrentFileContext(
                projectBase = basePath,
                filePathRelative = rel,
                fileHashSha256 = fileHashSha256,
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
