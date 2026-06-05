// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.quanta_dance.quanta.plugins.intellij.frontend.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.ChatAppIcons
import com.github.quanta_dance.quanta.plugins.intellij.frontend.coroutines.CoroutineScopeHolder
import com.github.quanta_dance.quanta.plugins.intellij.frontend.logging.FrontendBackendLogBridge
import com.github.quanta_dance.quanta.plugins.intellij.frontend.rpc.rpcProjectPath
import com.github.quanta_dance.quanta.plugins.intellij.models.Suggestion
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

private val logger = Logger.getInstance("RefactorSuggestionCard")
private val refactorCardPersistentStates = mutableMapOf<String, RefactorCardPersistentState>()

private fun frontendLog(
    project: Project,
    message: String,
) {
    QDLog.info(logger) { message }
    project.service<FrontendBackendLogBridge>().info(message)
}

private fun resolveFileType(filePath: String?) =
    filePath
        ?.substringAfterLast('/')
        ?.let { FileTypeManager.getInstance().getFileTypeByFileName(it) }
        ?: FileTypeManager.getInstance().getFileTypeByExtension("")

private const val MAX_CODE_BLOCK_HEIGHT = 320

private fun contentPreferredHeight(
    lineCount: Int,
    lineHeight: Int,
): Int = (lineCount.coerceAtLeast(1) * lineHeight).coerceAtMost(MAX_CODE_BLOCK_HEIGHT)

@Composable
fun refactorSuggestionCard(
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
    val suggestion =
        remember(item.filePath, message, currentText, suggestedText, originalRange) {
            buildSuggestion(item, message, currentText, suggestedText, originalRange)
        }
    val rpcScope = remember(project) { CoroutineScopeHolder.getInstance(project).getPluginScope() }

    val editorScheme = EditorColorsManager.getInstance().globalScheme
    val foreground = Color(editorScheme.defaultForeground.rgb)
    val background = Color(editorScheme.defaultBackground.rgb)
    val cardBorder = foreground.copy(alpha = 0.18f)
    val codeBorder = foreground.copy(alpha = 0.10f)
    val originalAccent = Color(0xFFD16D6D).copy(alpha = 0.9f)
    val suggestedAccent = Color(0xFF5FAF6B).copy(alpha = 0.95f)
    val actionAccent = foreground.copy(alpha = 0.75f)
    val persistentState =
        remember(item.callId) {
            refactorCardPersistentStates.getOrPut(item.callId) { RefactorCardPersistentState() }
        }
    var isOriginalExpanded by persistentState::isOriginalExpanded
    var isSuggestedExpanded by persistentState::isSuggestedExpanded
    var isApplying by remember(item.callId) { mutableStateOf(false) }
    var applyError by remember(item.callId) { mutableStateOf<String?>(null) }
    var actionState by persistentState::actionState
    var appliedRange by persistentState::appliedRange
    val linkTargetLine = appliedRange?.first ?: originalRange.first
    val displayedSuggestedRange = appliedRange ?: suggestedRange

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
                style =
                    JewelTheme.defaultTextStyle.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = foreground,
                    ),
            )
        }

        if (item.filePath != null) {
            Text(
                text =
                    "$fileLabel:${originalRange.first}-${originalRange.second}" +
                            " → ${displayedSuggestedRange.first}-${displayedSuggestedRange.second}",
                style =
                    JewelTheme.defaultTextStyle.copy(
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF69B7FF),
                        textDecoration = TextDecoration.Underline,
                    ),
                modifier =
                    Modifier.clickable {
                        item.filePath?.let { path ->
                            frontendLog(
                                project,
                                "RefactorSuggestionCard.openLink: $path:$linkTargetLine",
                            )
                            rpcScope.launch {
                                runCatching {
                                    QuantaBackendApi.getInstance().openProjectFileAtLine(
                                        project.rpcProjectPath(),
                                        path,
                                        linkTargetLine,
                                    )
                                }.onFailure { error ->
                                    QDLog.warn(logger) {
                                        "Failed to open linked refactor suggestion file: ${error.message}"
                                    }
                                }
                            }
                        }
                    },
            )
        }

        if (message.isNotBlank()) {
            Text(
                text = message,
                style =
                    JewelTheme.defaultTextStyle.copy(
                        color = foreground,
                    ),
            )
        }

        applyError?.let { error ->
            Text(
                text = error,
                style =
                    JewelTheme.defaultTextStyle.copy(
                        fontSize = 11.sp,
                        color = originalAccent,
                    ),
            )
        }

        sectionHeader(
            title = "Original",
            expanded = isOriginalExpanded,
            accent = originalAccent,
            actionAccent = actionAccent,
            onToggle = { isOriginalExpanded = !isOriginalExpanded },
            onCopy = { copyToClipboard(currentText.ifBlank { item.displayText }) },
        )

        if (isOriginalExpanded) {
            syntaxHighlightedBlock(
                project = project,
                text = currentText.ifBlank { item.displayText },
                filePath = item.filePath,
                startLine = originalRange.first,
                borderColor = codeBorder,
                scheme = editorScheme,
            )
        }

        sectionHeader(
            title = "Suggested",
            expanded = isSuggestedExpanded,
            accent = suggestedAccent,
            actionAccent = actionAccent,
            onToggle = { isSuggestedExpanded = !isSuggestedExpanded },
            onCopy = { copyToClipboard(suggestedText.ifBlank { detail }) },
        )

        if (isSuggestedExpanded) {
            syntaxHighlightedBlock(
                project = project,
                text = suggestedText.ifBlank { detail },
                filePath = item.filePath,
                startLine = suggestedRange.first,
                borderColor = codeBorder,
                scheme = editorScheme,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            actionButton(
                text = "Open",
                accent = actionAccent,
                onClick = {
                    item.filePath?.let { path ->
                        frontendLog(project, "RefactorSuggestionCard.open: $path:${linkTargetLine}")
                        rpcScope.launch {
                            runCatching {
                                QuantaBackendApi.getInstance().openProjectFileAtLine(
                                    project.rpcProjectPath(),
                                    path,
                                    originalRange.first,
                                )
                            }.onFailure {
                                QDLog.warn(logger) { "Failed to open suggested file: ${it.message}" }
                            }
                        }
                    }
                },
            )

            if (actionState == null) {
                actionButton(
                    text = if (isApplying) "Applying..." else "Apply",
                    accent = suggestedAccent,
                    enabled = !isApplying,
                    onClick = {
                        if (suggestion == null || isApplying) return@actionButton
                        frontendLog(project, "RefactorSuggestionCard.apply requested: ${item.displayText}")
                        applyError = null
                        isApplying = true
                        rpcScope.launch {
                            runCatching {
                                QuantaBackendApi
                                    .getInstance()
                                    .applyRefactorSuggestion(project.rpcProjectPath(), suggestion)
                            }.onSuccess { result ->
                                EventQueue.invokeLater {
                                    if (result.applied) {
                                        actionState = RefactorActionState.APPLIED
                                        isSuggestedExpanded = false
                                        isOriginalExpanded = false
                                        appliedRange =
                                            if (result.newStartLine != null && result.newEndLine != null) {
                                                Pair(result.newStartLine!!, result.newEndLine!!)
                                            } else {
                                                appliedRange
                                            }
                                    } else {
                                        applyError = result.errorMessage ?: "Failed to apply suggestion."
                                    }
                                    isApplying = false
                                }
                            }.onFailure {
                                QDLog.warn(logger) { "Failed to apply suggested refactor: ${it.message}" }
                                EventQueue.invokeLater {
                                    applyError = it.message ?: "Failed to apply suggestion."
                                    isApplying = false
                                }
                            }
                        }
                    },
                )

                actionButton(
                    text = "Decline",
                    accent = originalAccent,
                    enabled = !isApplying,
                    onClick = {
                        if (isApplying) return@actionButton
                        frontendLog(project, "RefactorSuggestionCard.decline requested: ${item.displayText}")
                        applyError = null
                        actionState = RefactorActionState.DECLINED
                        isSuggestedExpanded = false
                        isOriginalExpanded = false
                    },
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        key =
                            if (actionState == RefactorActionState.APPLIED) {
                                ChatAppIcons.ToolStatus.success
                            } else {
                                ChatAppIcons.ToolStatus.failed
                            },
                        contentDescription = null,
                    )
                    Text(
                        text = if (actionState == RefactorActionState.APPLIED) "Applied" else "Declined",
                        style =
                            JewelTheme.defaultTextStyle.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color =
                                    if (actionState == RefactorActionState.APPLIED) {
                                        suggestedAccent
                                    } else {
                                        originalAccent
                                    },
                            ),
                    )
                }
            }
        }
    }
}

@Suppress("ktlint:standard:max-line-length")
@Composable
private fun syntaxHighlightedBlock(
    project: Project,
    text: String,
    filePath: String?,
    startLine: Int,
    borderColor: Color,
    scheme: EditorColorsScheme,
) {
    val fileType = resolveFileType(filePath)

    val editorData =
        remember(text, filePath, startLine, scheme) {
            val document =
                EditorFactory
                    .getInstance()
                    .createDocument(text)
            val editor =
                EditorFactory
                    .getInstance()
                    .createViewer(document) as EditorEx

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
            editor.settings.setAdditionalLinesCount(0)
            editor.settings.isVirtualSpace = false
            editor.settings.isAdditionalPageAtBottom = false
            editor.setHorizontalScrollbarVisible(false)
            editor.setVerticalScrollbarVisible(true)
            editor.highlighter =
                filePath
                    ?.let {
                        EditorHighlighterFactory
                            .getInstance()
                            .createEditorHighlighter(scheme, it, project)
                    }
                    ?: EditorHighlighterFactory
                        .getInstance()
                        .createEditorHighlighter(fileType, scheme, project)

            editor.gutterComponentEx.setLineNumberConverter(
                object : LineNumberConverter {
                    override fun convert(
                        editor: com.intellij.openapi.editor.Editor,
                        line: Int,
                    ): Int = line + startLine - 1

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

            val preferredHeight = contentPreferredHeight(document.lineCount, editor.lineHeight)
            val componentWidth = editor.component.preferredSize.width
            val scrollPaneWidth = editor.scrollPane.preferredSize.width

            editor.component.preferredSize =
                Dimension(
                    componentWidth,
                    preferredHeight,
                )
            editor.scrollPane.preferredSize =
                Dimension(
                    scrollPaneWidth,
                    preferredHeight,
                )

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
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Color(scheme.defaultBackground.rgb), RoundedCornerShape(8.dp))
                .border(1.dp, borderColor, RoundedCornerShape(8.dp)),
    )
}

private fun setPreferredHeight(
    target: javax.swing.JComponent,
    width: Int,
    height: Int,
) {
    target.preferredSize = Dimension(width, height)
}

private fun extractLineRange(displayText: String): Pair<Int, Int> {
    val match =
        Regex("(\\d+)-(\\d+)")
            .find(displayText)
            ?: return 1 to 1
    return match.groupValues[1].toInt() to match.groupValues[2].toInt()
}

private fun buildSuggestion(
    item: ToolExecutionItem,
    message: String,
    currentText: String,
    suggestedText: String,
    originalRange: Pair<Int, Int>,
): Suggestion? {
    val filePath = item.filePath ?: return null
    if (currentText.isBlank() || suggestedText.isBlank()) return null
    return Suggestion(
        file = filePath,
        original_line_from = originalRange.first,
        original_line_to = originalRange.second,
        suggested_code = suggestedText,
        replaced_code = currentText,
        message = message,
    )
}

private class RefactorCardPersistentState {
    var isOriginalExpanded by mutableStateOf(false)
    var isSuggestedExpanded by mutableStateOf(true)
    var actionState by mutableStateOf<RefactorActionState?>(null)
    var appliedRange by mutableStateOf<Pair<Int, Int>?>(null)
}

private enum class RefactorActionState {
    APPLIED,
    DECLINED,
}

private fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

@Composable
private fun actionButton(
    text: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val background = accent.copy(alpha = if (enabled) 0.14f else 0.08f)
    val border = accent.copy(alpha = if (enabled) 0.40f else 0.18f)
    val textColor = if (enabled) accent else accent.copy(alpha = 0.55f)

    Text(
        text = text,
        style =
            JewelTheme.defaultTextStyle.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
            ),
        modifier =
            Modifier
                .background(background, RoundedCornerShape(8.dp))
                .border(1.dp, border, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun sectionHeader(
    title: String,
    expanded: Boolean,
    accent: Color,
    actionAccent: Color,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = if (expanded) "▼ $title" else "▶ $title",
            style =
                JewelTheme.defaultTextStyle.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                ),
            modifier = Modifier.clickable { onToggle() },
        )
        IconButton(onClick = onCopy) {
            Icon(
                key = AllIconsKeys.Actions.Copy,
                contentDescription = "Copy $title",
            )
        }
    }
}
