// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project

@JsonClassDescription("Open a project file in the editor. REQUIRED ARGUMENT: filePath. Use filePath, not path. Example: {\"filePath\": \"README.md\"}. Optionally move the caret to a line/column or select a range.")
data class OpenFileInEditorTool(
    @field:JsonPropertyDescription("REQUIRED. Use this exact field name: filePath. Relative to the project root path to the file to open. Example: README.md")
    val filePath: String,

    @field:JsonPropertyDescription("1-based line number to place the caret at. If null or <= 0, the file is opened without moving caret.")
    val line: Int? = null,

    @field:JsonPropertyDescription("0-based column to place the caret at. Ignored if 'line' is not provided or <= 0.")
    val column: Int? = null,

    @field:JsonPropertyDescription("If true, activates the editor after opening. Default: true")
    val focus: Boolean = true,

    @field:JsonPropertyDescription(
        "Optional selection start line (1-based). If provided with end, selection takes precedence over caret movement.",
    )
    val selectionStartLine: Int? = null,

    @field:JsonPropertyDescription("Optional selection start column (0-based)")
    val selectionStartColumn: Int? = null,

    @field:JsonPropertyDescription("Optional selection end line (1-based)")
    val selectionEndLine: Int? = null,

    @field:JsonPropertyDescription("Optional selection end column (0-based)")
    val selectionEndColumn: Int? = null,
) : ToolInterface<String> {

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

    override fun execute(project: Project): String {
        val basePath = PathUtils.projectRootPath(project) ?: return "Project base path not found."

        val resolved =
            try {
                PathUtils.resolveWithinProject(project, filePath)
            } catch (e: IllegalArgumentException) {
                addMsg(project, "Open file - rejected", e.message ?: "Invalid path")
                return e.message ?: "Invalid path"
            }

        val relToBase = PathUtils.relativizeToProject(basePath, resolved)

        // Resolve via project VFS first so remote/backed files work too
        val vFile =
            try {
                PathUtils.resolveVirtualFileWithinProject(project, filePath)
            } catch (_: Throwable) {
                null
            }

        if (vFile == null || vFile.isDirectory) {
            val reason = if (vFile == null) "File not found" else "Path is a directory"
            addMsg(project, "Open file - failed", "$relToBase ($reason)")
            return reason
        }

        // Compute caret target (line 1-based -> 0-based for descriptor)
        val targetLine = (line ?: 0).takeIf { it > 0 }?.minus(1)
        val targetColumn = if (targetLine != null) (column ?: 0).coerceAtLeast(0) else null

        // Selection inputs (1-based lines -> 0-based for LogicalPosition)
        val selStartLine0 = (selectionStartLine ?: 0).takeIf { it > 0 }?.minus(1)
        val selEndLine0 = (selectionEndLine ?: 0).takeIf { it > 0 }?.minus(1)
        val selStartCol0 = selectionStartColumn?.coerceAtLeast(0)
        val selEndCol0 = selectionEndColumn?.coerceAtLeast(0)
        val wantSelection = selStartLine0 != null && selEndLine0 != null && selStartCol0 != null && selEndCol0 != null

        return try {
            var opened = false
            ApplicationManager.getApplication().invokeAndWait {
                val descriptor =
                    if (!wantSelection && targetLine != null && targetColumn != null) {
                        OpenFileDescriptor(project, vFile, targetLine, targetColumn)
                    } else if (!wantSelection && targetLine != null) {
                        OpenFileDescriptor(project, vFile, targetLine, 0)
                    } else {
                        OpenFileDescriptor(project, vFile, 0)
                    }
                val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, focus)

                opened = editor != null

                if (editor != null && wantSelection) {
                    val startPos = LogicalPosition(selStartLine0!!, selStartCol0!!)
                    val endPos = LogicalPosition(selEndLine0!!, selEndCol0!!)
                    val startOffset = editor.logicalPositionToOffset(startPos)
                    val endOffset = editor.logicalPositionToOffset(endPos)
                    val caret = editor.caretModel.currentCaret
                    caret.moveToOffset(startOffset)
                    caret.setSelection(startOffset, endOffset)
                } else if (editor != null && targetLine != null) {
                    val caret = editor.caretModel.currentCaret
                    val pos = LogicalPosition(targetLine, targetColumn ?: 0)
                    caret.removeSelection()
                    caret.moveToLogicalPosition(pos)
                }
            }

            val details =
                buildString {
                    append(relToBase)
                    if (wantSelection) {
                        append(" @ selection=")
                        append("[")
                        append((selectionStartLine ?: 0))
                        append(":")
                        append((selectionStartColumn ?: 0))
                        append(" -> ")
                        append((selectionEndLine ?: 0))
                        append(":")
                        append((selectionEndColumn ?: 0))
                        append("]")
                    } else if (targetLine != null) {
                        append(" @ line=")
                        append(targetLine + 1)
                        append(", col=")
                        append(targetColumn ?: 0)
                    }
                }
            addMsg(project, "Open file", details)
            if (opened) "Opened $relToBase" else "Opened (no editor) $relToBase"
        } catch (e: Throwable) {
            if (e is ProcessCanceledException) throw e
            val msg = e.message ?: "Failed to open file"
            addMsg(project, "Open file - error", "$relToBase: $msg")
            msg
        }
    }
}
