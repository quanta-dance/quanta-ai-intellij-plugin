// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.toolWindow.cards

import com.github.quanta_dance.quanta.plugins.intellij.models.Suggestion
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.MouseInfo
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.UIManager

class SuggestionCardPanel(
    private var suggestion: Suggestion,
) :
    JPanel(BorderLayout()) {
    private fun isActionable(): Boolean {
        return suggestion.suggested_code.isNotBlank() && suggestion.replaced_code.isNotBlank() &&
            suggestion.original_line_from > 0 && suggestion.original_line_to >= suggestion.original_line_from
    }

    private lateinit var fileLabel: JBLabel
    private var actionsPanel: JBPanel<Nothing>? = null
    private var centerPanel: JBPanel<Nothing>? = null
    private var docListener: DocumentListener? = null
    private var docListenerDisposable: Disposable? = null

    init {
        initializeUI()
    }

    override fun addNotify() {
        super.addNotify()
        if (docListenerDisposable == null) {
            docListenerDisposable = Disposer.newDisposable("SuggestionCardPanel.docListener")
        }
        ProjectManager.getInstance().openProjects.firstOrNull()?.let { attachDocumentListener(it) }
    }

    override fun removeNotify() {
        detachDocumentListener()
        super.removeNotify()
    }

    private fun attachDocumentListener(project: Project) {
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath + "/" + suggestion.file) ?: return
        val doc = FileDocumentManager.getInstance().getDocument(vFile) ?: return
        if (docListener != null) return
        docListener =
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    // Re-map on any change and update label lines
                    SwingUtilities.invokeLater {
                        remapAndUpdateLabel(project)
                    }
                }
            }
        val disposable = docListenerDisposable
        if (disposable != null) {
            doc.addDocumentListener(docListener!!, disposable)
        } else {
            // Fallback (should not happen): keep old behavior
            @Suppress("DEPRECATION")
            doc.addDocumentListener(docListener!!)
        }
    }

    private fun detachDocumentListener() {
        ProjectManager.getInstance().openProjects.firstOrNull()?.let { project ->
            val vFile = LocalFileSystem.getInstance().findFileByPath(project.basePath + "/" + suggestion.file)
            val doc = vFile?.let { FileDocumentManager.getInstance().getDocument(it) }
            if (docListener != null && doc != null) {
                doc.removeDocumentListener(docListener!!)
                docListener = null
            }
        }
        docListenerDisposable?.let {
            Disposer.dispose(it)
        }
        docListenerDisposable = null
    }

    private fun scrollToOffsetAndSelect(
        project: Project,
        filePath: String,
        startOffset: Int,
        endOffset: Int,
    ) {
        val virtualFile: VirtualFile? = LocalFileSystem.getInstance().findFileByPath(project.basePath + "/" + filePath)
        virtualFile?.let {
            OpenFileDescriptor(project, it, startOffset).navigate(true)
            FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
                val safeStart = startOffset.coerceIn(0, editor.document.textLength)
                val safeEnd = endOffset.coerceIn(0, editor.document.textLength)
                editor.caretModel.moveToOffset(safeStart)
                editor.selectionModel.setSelection(safeStart, safeEnd)
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
        }
    }

    private fun initializeUI() {
        val actionable = isActionable()
        border = BorderFactory.createTitledBorder(if (actionable) "Suggestion (actionable)" else "Suggestion (info)")
        maximumSize = Dimension(500, Int.MAX_VALUE)

        centerPanel =
            JBPanel<Nothing>(BorderLayout()).also { cp ->
                if (actionable) {
                    val codeViewerField = createEditorField().apply { minimumSize = Dimension(400, 20) }
                    val codeViewerScrollPane = JBScrollPane(codeViewerField).apply { border = BorderFactory.createEmptyBorder() }
                    cp.add(codeViewerScrollPane, BorderLayout.CENTER)
                } else {
                    val info =
                        JTextArea("This suggestion is descriptive only and cannot be applied automatically.").apply {
                            isEditable = false
                            lineWrap = true
                            wrapStyleWord = true
                            foreground = JBColor.GRAY
                            background = UIManager.getColor("Label.background")
                            border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
                        }
                    cp.add(info, BorderLayout.CENTER)
                }
            }

        val descriptionArea =
            JTextArea(suggestion.message).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = font.deriveFont(font.size2D - 1)
                foreground = JBColor.GRAY
                background = UIManager.getColor("Label.background")
            }
        val descriptionScrollPane = JBScrollPane(descriptionArea).apply { border = BorderFactory.createEmptyBorder(5, 5, 5, 1) }

        val fileName = Paths.get(suggestion.file).fileName.toString()
        fileLabel =
            JBLabel(fileName + ":" + suggestion.original_line_from + "-" + suggestion.original_line_to).apply {
                font = font.deriveFont(font.size2D - 3)
                foreground = JBColor.BLUE
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = BorderFactory.createEmptyBorder(2, 5, 2, 1)
                toolTipText = suggestion.file
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            ProjectManager.getInstance().openProjects.firstOrNull()?.let { project ->
                                val (start, end) = remapOffsets(project) ?: return
                                scrollToOffsetAndSelect(project, suggestion.file, start, end)
                            }
                        }
                    },
                )
            }

        actionsPanel =
            JBPanel<Nothing>(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                val ignoreBtn =
                    JButton("Ignore").apply {
                        toolTipText = "Ignore this suggestion."
                        addActionListener {
                            this@SuggestionCardPanel.isVisible = false
                            this@SuggestionCardPanel.parent?.remove(this@SuggestionCardPanel)
                            this@SuggestionCardPanel.parent?.revalidate()
                            this@SuggestionCardPanel.parent?.repaint()
                        }
                    }
                val applyBtn =
                    JButton("Apply").apply {
                        isEnabled = actionable
                        toolTipText = if (actionable) "Apply this change." else "Non-actionable suggestion"
                        addActionListener {
                            if (!actionable) return@addActionListener
                            ProjectManager.getInstance().openProjects.firstOrNull()?.let { project ->
                                applyDirectly(project)
                            }
                        }
                    }
                add(ignoreBtn)
                add(applyBtn)
            }

        val topPanel =
            JBPanel<Nothing>(BorderLayout()).apply {
                add(fileLabel, BorderLayout.NORTH)
                add(descriptionScrollPane, BorderLayout.CENTER)
                add(actionsPanel, BorderLayout.SOUTH)
            }

        add(topPanel, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
    }

    private fun plannedOffsets(project: Project): Pair<Int, Int>? {
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath + "/" + suggestion.file) ?: return null
        val doc = FileDocumentManager.getInstance().getDocument(vFile) ?: return null
        val startLineIdx = (suggestion.original_line_from - 1).coerceAtLeast(0).coerceAtMost(doc.lineCount - 1)
        val endLineIdx = (suggestion.original_line_to - 1).coerceAtLeast(0).coerceAtMost(doc.lineCount - 1)
        val startOffset = doc.getLineStartOffset(startLineIdx)
        val endOffset = doc.getLineEndOffset(endLineIdx)
        return startOffset to endOffset
    }

    private fun fuzzyFind(
        docText: CharSequence,
        needle: String,
        windowStart: Int,
        windowEnd: Int,
    ): Int? {
        if (needle.isEmpty()) return null
        val start = windowStart.coerceAtLeast(0)
        val end = windowEnd.coerceAtMost(docText.length)
        if (start >= end) return null
        val idx = docText.indexOf(needle, startIndex = start)
        return if (idx in start until end) idx else null
    }

    private fun remapOffsets(project: Project): Pair<Int, Int>? {
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath + "/" + suggestion.file) ?: return null
        val doc = FileDocumentManager.getInstance().getDocument(vFile) ?: return null
        val (plannedStart, plannedEnd) = plannedOffsets(project) ?: return null
        var start = plannedStart
        var end = plannedEnd
        val currentSegment = doc.charsSequence.subSequence(start, end).toString()
        if (currentSegment != suggestion.replaced_code) {
            val windowStart = (plannedStart - 1000).coerceAtLeast(0)
            val windowEnd = (plannedEnd + 1000).coerceAtMost(doc.textLength)
            val found =
                fuzzyFind(doc.charsSequence, suggestion.replaced_code, windowStart, windowEnd)
                    ?: doc.charsSequence.indexOf(suggestion.replaced_code).takeIf { it >= 0 }
            if (found != null) {
                start = found
                end = found + suggestion.replaced_code.length
            }
        }
        return start to end
    }

    private fun remapAndUpdateLabel(project: Project) {
        val offsets = remapOffsets(project) ?: return
        val vFile = LocalFileSystem.getInstance().findFileByPath(project.basePath + "/" + suggestion.file) ?: return
        val doc = FileDocumentManager.getInstance().getDocument(vFile) ?: return
        val start = offsets.first
        val end = offsets.second
        val newStartLine = doc.getLineNumber(start) + 1
        val newEndLine = doc.getLineNumber(end).coerceAtLeast(doc.getLineNumber(start)) + 1
        fileLabel.text = Paths.get(suggestion.file).fileName.toString() + ":" + newStartLine + "-" + newEndLine
        suggestion = suggestion.copy(original_line_from = newStartLine, original_line_to = newEndLine)
    }

    private fun markAppliedUI(
        newStartOffset: Int,
        newEndOffset: Int,
        project: Project,
    ) {
        // Hide code viewer area
        centerPanel?.isVisible = false
        // Disable and remove action buttons
        actionsPanel?.let { panel ->
            panel.components.forEach { it.isEnabled = false }
            panel.parent?.remove(panel)
            actionsPanel = null
        }
        // Show applied status
        val status =
            JLabel("Applied").apply {
                foreground = JBColor(0x2E7D32, 0x81C784)
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            }
        add(status, BorderLayout.SOUTH)
        revalidate()
        repaint()
        // Select applied region in editor
        scrollToOffsetAndSelect(project, suggestion.file, newStartOffset, newEndOffset)
    }

    private fun applyDirectly(project: Project) {
        val path = project.basePath + "/" + suggestion.file
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: run { return }
        val doc = FileDocumentManager.getInstance().getDocument(vFile) ?: run { return }

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val offsets = remapOffsets(project) ?: return@runWriteCommandAction
                val start = offsets.first
                val end = offsets.second

                // Apply without confirmations
                doc.replaceString(start, end, suggestion.suggested_code)
                PsiDocumentManager.getInstance(project).commitDocument(doc)

                // Update link line numbers to the new applied region
                val newStartLine = doc.getLineNumber(start) + 1
                val newEndLine = doc.getLineNumber(start + suggestion.suggested_code.length).coerceAtLeast(doc.getLineNumber(start)) + 1
                fileLabel.text = Paths.get(suggestion.file).fileName.toString() + ":" + newStartLine + "-" + newEndLine

                // Update model with new lines
                suggestion =
                    suggestion.copy(
                        original_line_from = newStartLine,
                        original_line_to = newEndLine,
                    )

                // Reflect applied state in UI and select region
                markAppliedUI(start, start + suggestion.suggested_code.length, project)
            }
        }
    }

    private fun createEditorField(): EditorTextField {
        val extension = FileUtilRt.getExtension(suggestion.file)
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(extension)
        val document =
            EditorFactory.getInstance().createDocument(
                suggestion.suggested_code,
            )
        return EditorTextField(document, null, fileType, true, false).apply {
            preferredSize = null

            if (suggestion.replaced_code.isNotEmpty()) {
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent?) {
                            showOriginalCodeTooltip()
                        }

                        override fun mouseExited(e: MouseEvent?) {
                            hideOriginalCodeTooltip()
                        }
                    },
                )
            }

            addSettingsProvider { editor ->
                configureEditor(editor as EditorEx)
                editor.highlighter =
                    EditorHighlighterFactory.getInstance().createEditorHighlighter(fileType, editor.colorsScheme, null)
            }
        }
    }

    private var tooltipWindow: JWindow? = null

    private fun showOriginalCodeTooltip() {
        if (tooltipWindow == null) {
            val extension = FileUtilRt.getExtension(suggestion.file)
            val fileType = FileTypeManager.getInstance().getFileTypeByExtension(extension)
            val tooltipEditorField =
                EditorTextField(
                    EditorFactory.getInstance().createDocument(suggestion.replaced_code),
                    null,
                    fileType,
                    true,
                    false,
                ).apply {
                    border = BorderFactory.createTitledBorder("Original code")
                    preferredSize = Dimension(600, 200)
                    addSettingsProvider { editor ->
                        editor.colorsScheme = editor.colorsScheme
                        editor.highlighter =
                            EditorHighlighterFactory.getInstance()
                                .createEditorHighlighter(fileType, editor.colorsScheme, null)
                    }
                }

            tooltipWindow =
                JWindow().apply {
                    layout = BorderLayout()
                    add(tooltipEditorField, BorderLayout.CENTER)
                    preferredSize = tooltipEditorField.preferredSize
                    size = tooltipEditorField.preferredSize
                    isVisible = false
                }
        }

        val location = MouseInfo.getPointerInfo().location
        tooltipWindow?.apply {
            setLocation(location.x + 10, location.y + 10)
            isVisible = true
        }
    }

    private fun hideOriginalCodeTooltip() {
        tooltipWindow?.isVisible = false
    }

    private fun configureEditor(editor: EditorEx) {
        editor.apply {
            isOneLineMode = false
            colorsScheme = editor.colorsScheme
            settings.isWhitespacesShown = false
            settings.isLineMarkerAreaShown = true
            settings.isLineNumbersShown = true
            settings.isFoldingOutlineShown = true
            settings.isUseSoftWraps = true
            settings.isCaretRowShown = true

            val baseOffset = (suggestion.original_line_from - 1).coerceAtLeast(0)
            val converter =
                object : LineNumberConverter {
                    override fun convert(
                        editor: Editor,
                        line: Int,
                    ): Int = line + baseOffset

                    override fun getMaxLineNumber(editor: Editor): Int = editor.document.lineCount + baseOffset
                }
            gutterComponentEx.setLineNumberConverter(converter)
        }
    }
}
