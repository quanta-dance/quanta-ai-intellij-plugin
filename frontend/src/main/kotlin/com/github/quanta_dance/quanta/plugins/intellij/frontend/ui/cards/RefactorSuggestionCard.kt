// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.EventQueue
import java.awt.Font
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
                        frontendLog(project, "RefactorSuggestionCard.open: $path:$linkTargetLine")
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
    val highlightedText =
        remember(text, filePath, scheme) {
            val highlighter =
                filePath
                    ?.let {
                        EditorHighlighterFactory
                            .getInstance()
                            .createEditorHighlighter(scheme, it, project)
                    }
                    ?: EditorHighlighterFactory
                        .getInstance()
                        .createEditorHighlighter(fileType, scheme, project)

            highlighter.setText(text)
            buildAnnotatedString {
                append(text)
                if (text.isNotEmpty()) {
                    val iterator = highlighter.createIterator(0)
                    while (!iterator.atEnd()) {
                        val attributes = iterator.textAttributes
                        addStyle(
                            SpanStyle(
                                color =
                                    attributes.foregroundColor
                                        ?.let { Color(it.rgb) }
                                        ?: Color(scheme.defaultForeground.rgb),
                                background =
                                    attributes.backgroundColor
                                        ?.let { Color(it.rgb) }
                                        ?: Color.Unspecified,
                                fontWeight =
                                    if (attributes.fontType and Font.BOLD != 0) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                fontStyle =
                                    if (attributes.fontType and Font.ITALIC != 0) {
                                        FontStyle.Italic
                                    } else {
                                        FontStyle.Normal
                                    },
                            ),
                            iterator.start,
                            iterator.end,
                        )
                        iterator.advance()
                    }
                }
            }
        }
    val lineNumbers =
        remember(text, startLine) {
            List(text.lineSequence().count().coerceAtLeast(1)) { index -> startLine + index }
                .joinToString("\n")
        }
    val scrollState = rememberScrollState()
    val background = Color(scheme.defaultBackground.rgb)

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = MAX_CODE_BLOCK_HEIGHT.dp)
                .background(background, RoundedCornerShape(8.dp))
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .verticalScroll(scrollState)
                .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = lineNumbers,
                style =
                    JewelTheme.defaultTextStyle.copy(
                        color = Color(scheme.defaultForeground.rgb).copy(alpha = 0.55f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                modifier = Modifier.padding(end = 10.dp),
            )
            SelectionContainer(modifier = Modifier.weight(1f)) {
                Text(
                    text = highlightedText,
                    style =
                        JewelTheme.defaultTextStyle.copy(
                            color = Color(scheme.defaultForeground.rgb),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                )
            }
        }
    }
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
