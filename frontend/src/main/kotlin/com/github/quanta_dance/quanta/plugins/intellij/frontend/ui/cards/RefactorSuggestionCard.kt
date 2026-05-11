package com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.quanta_dance.quanta.plugins.intellij.frontend.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.ChatAppIcons
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.FrontendLogDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.FrontendLogLevel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import com.intellij.ui.EditorTextField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text

private val refactorCardScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val logger = Logger.getInstance("RefactorSuggestionCard")

private fun frontendLog(project: Project, message: String) {
    QDLog.info(logger) { message }
    refactorCardScope.launch {
        runCatching {
            QuantaBackendApi.getInstance().logFrontend(
                project.projectId(),
                FrontendLogDto(level = FrontendLogLevel.INFO, message = message),
            )
        }
    }
}

private fun resolveFileType(extension: String) =
    FileTypeManager.getInstance().getFileTypeByExtension(extension)

@Composable
fun RefactorSuggestionCard(
    project: Project,
    item: ToolExecutionItem,
) {
    val detail = item.detailText.orEmpty()
    val lines = detail.lines()
    val message = lines.firstOrNull().orEmpty().ifBlank { item.displayText }

    val currentIndex = lines.indexOfFirst { it.trim() == "Current:" }
    val suggestedIndex = lines.indexOfFirst { it.trim() == "Suggested:" }

    val currentText =
        if (currentIndex >= 0 && suggestedIndex > currentIndex) {
            lines.subList(currentIndex + 1, suggestedIndex).joinToString("\n").trim()
        } else {
            ""
        }

    val suggestedText =
        if (suggestedIndex >= 0) {
            lines.drop(suggestedIndex + 1).joinToString("\n").trim()
        } else {
            ""
        }

    val fileLabel = item.filePath?.substringAfterLast('/') ?: item.displayText
    val originalRange = extractLineRange(item.displayText)
    val suggestedRange =
        originalRange.first to (originalRange.first + suggestedText.lineSequence().count().coerceAtLeast(1) - 1)

    val editorScheme = EditorColorsManager.getInstance().globalScheme
    val foreground = Color(editorScheme.defaultForeground.rgb)
    val background = Color(editorScheme.defaultBackground.rgb)
    val cardBorder = foreground.copy(alpha = 0.18f)
    val codeBorder = foreground.copy(alpha = 0.10f)
    val originalAccent = foreground.copy(alpha = 0.70f)
    val suggestedAccent = foreground.copy(alpha = 0.70f)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background, RoundedCornerShape(12.dp))
                .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
                .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(key = ChatAppIcons.ToolStatus.success, contentDescription = null)
            Text(
                text = "Refactor suggestion",
                style = JewelTheme.defaultTextStyle.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = foreground,
                ),
            )
        }

        if (item.filePath != null) {
            Text(
                text = "$fileLabel:${originalRange.first}-${originalRange.second} → ${suggestedRange.first}-${suggestedRange.second}",
                style = JewelTheme.defaultTextStyle.copy(
                    fontSize = 10.sp,
                    color = foreground.copy(alpha = 0.75f),
                ),
            )
        }

        if (message.isNotBlank()) {
            Text(
                text = message,
                style = JewelTheme.defaultTextStyle.copy(
                    fontSize = 10.sp,
                    color = foreground,
                ),
            )
        }

        Text(
            text = "Original",
            style = JewelTheme.defaultTextStyle.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = originalAccent,
            ),
        )

        SyntaxHighlightedBlock(
            text = currentText.ifBlank { item.displayText },
            filePath = item.filePath,
            startLine = originalRange.first,
            borderColor = codeBorder,
            scheme = editorScheme,
        )

        Text(
            text = "Suggested",
            style = JewelTheme.defaultTextStyle.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = suggestedAccent,
            ),
        )

        SyntaxHighlightedBlock(
            text = suggestedText.ifBlank { detail },
            filePath = item.filePath,
            startLine = suggestedRange.first,
            borderColor = codeBorder,
            scheme = editorScheme,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                onClick = {
                    item.filePath?.let { path ->
                        frontendLog(project, "RefactorSuggestionCard.open: $path")
                        refactorCardScope.launch {
                            runCatching {
                                QuantaBackendApi.getInstance().openProjectFile(project.projectId(), path)
                            }.onFailure {
                                QDLog.warn(logger) { "Failed to open suggested file: ${it.message}" }
                            }
                        }
                    }
                },
            ) {
                Text("Open")
            }

            IconButton(
                onClick = {
                    frontendLog(project, "RefactorSuggestionCard.apply requested: ${item.displayText}")
                },
            ) {
                Text("Apply")
            }
        }
    }
}

@Composable
private fun SyntaxHighlightedBlock(
    text: String,
    filePath: String?,
    startLine: Int,
    borderColor: Color,
    scheme: EditorColorsScheme,
) {
    val extension = filePath?.substringAfterLast('.', "") ?: ""
    val fileType = resolveFileType(extension)
    if (extension == "go" && fileType.name == "Plain Text") {
        QDLog.warn(logger) { "RefactorSuggestionCard.go highlighting fallback: fileType=${fileType.name}" }
    }

    val editorData = remember(text, filePath, startLine, scheme) {
        val document = EditorFactory.getInstance().createDocument(text)
        val editor = EditorFactory.getInstance().createViewer(document) as EditorEx

        editor.isOneLineMode = false
        editor.colorsScheme = scheme
        editor.backgroundColor = scheme.defaultBackground
        editor.setFontSize(11f)
        editor.settings.isLineMarkerAreaShown = true
        editor.settings.isLineNumbersShown = true
        editor.settings.isFoldingOutlineShown = true
        editor.settings.isUseSoftWraps = true
        editor.settings.isCaretRowShown = false
        editor.settings.isWhitespacesShown = false
        editor.settings.isAdditionalPageAtBottom = false
        editor.setHorizontalScrollbarVisible(false)
        editor.setVerticalScrollbarVisible(false)
        editor.highlighter =
            EditorHighlighterFactory.getInstance().createEditorHighlighter(fileType, scheme, null)

        editor.gutterComponentEx.setLineNumberConverter(
            object : LineNumberConverter {
                override fun convert(editor: com.intellij.openapi.editor.Editor, line: Int): Int =
                    line + startLine - 1

                override fun getMaxLineNumber(editor: com.intellij.openapi.editor.Editor): Int =
                    editor.document.lineCount + startLine - 1
            },
        )

        editor.component.background = scheme.defaultBackground
        editor.contentComponent.background = scheme.defaultBackground
        editor.scrollPane.background = scheme.defaultBackground
        editor.scrollPane.viewport.background = scheme.defaultBackground
        editor.gutterComponentEx.background = scheme.defaultBackground
        editor.component.isOpaque = true
        editor.contentComponent.isOpaque = true
        editor.scrollPane.isOpaque = true
        editor.scrollPane.viewport.isOpaque = true
        editor.gutterComponentEx.isOpaque = true

        editor to document
    }

    DisposableEffect(editorData) {
        onDispose {
            val (editor, document) = editorData
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    SwingPanel(
        factory = {
            javax.swing.JPanel(java.awt.BorderLayout()).apply {
                isOpaque = true
                background = scheme.defaultBackground
                add(editorData.first.component, java.awt.BorderLayout.CENTER)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(scheme.defaultBackground.rgb), RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp)),
    )
}

private fun extractLineRange(displayText: String): Pair<Int, Int> {
    val match = Regex("(\\d+)-(\\d+)").find(displayText) ?: return 1 to 1
    return match.groupValues[1].toInt() to match.groupValues[2].toInt()
}