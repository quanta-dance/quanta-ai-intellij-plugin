// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.github.quanta_dance.quanta.plugins.intellij.frontend.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.ChatAppColors
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.ChatAppIcons
import com.github.quanta_dance.quanta.plugins.intellij.frontend.components.TypingIndicator
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.cards.RefactorSuggestionCard
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.FrontendLogDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.FrontendLogLevel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text

private val logger = Logger.getInstance("ToolExecutionLink")
private val linkLogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Composable
fun MessageBubble(
    project: Project,
    message: ChatMessage,
    modifier: Modifier = Modifier,
    isMatchingSearch: Boolean = false,
    isHighlightedInSearch: Boolean = false,
) {
    if (message.isAIThinkingMessage()) {
        ThinkingMessageRow(message = message, modifier = modifier)
        return
    }

    val isMyMessage = message.isMyMessage
    val messageShape = RoundedCornerShape(
        topStart = 14.dp,
        topEnd = 14.dp,
        bottomStart = if (isMyMessage) 14.dp else 5.dp,
        bottomEnd = if (isMyMessage) 5.dp else 14.dp,
    )
    val messageBackgroundColor =
        when {
            message.isToolMessage() -> Color.Gray.copy(alpha = 0.18f)
            isHighlightedInSearch && isMyMessage -> ChatAppColors.MessageBubble.mySearchHighlightedBackground
            isHighlightedInSearch && !isMyMessage -> ChatAppColors.MessageBubble.othersSearchHighlightedBackground
            isMyMessage -> ChatAppColors.MessageBubble.myBackground
            else -> ChatAppColors.MessageBubble.othersBackground
        }

    Row(
        modifier =
            modifier
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = if (isMyMessage) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(min = 120.dp, max = 420.dp)
                    .wrapContentSize()
                    .background(messageBackgroundColor, messageShape)
                    .messageBorder(messageShape, isMyMessage, isHighlightedInSearch, isMatchingSearch)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            MessageHeader(message)
            if (message.toolItems.isNotEmpty()) {
                ToolExecutionGroup(project = project, toolItems = message.toolItems)
                if (message.content.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            if (message.content.isNotBlank()) {
                SelectionContainer {
                    MessageContent(message)
                }
            }
        }
    }
}

@Composable
private fun ToolExecutionGroup(
    project: Project,
    toolItems: List<ToolExecutionItem>,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        toolItems.forEach { item ->
            if (item.toolName == "CodeRefactorSuggester" && item.detailText?.contains("Suggested:") == true) {
                RefactorSuggestionCard(project = project, item = item)
            } else {
                ToolExecutionRow(project = project, item = item)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ToolExecutionRow(
    project: Project,
    item: ToolExecutionItem,
) {
    var hovered by remember(item.callId) { mutableStateOf(false) }
    var statusHovered by remember(item.callId) { mutableStateOf(false) }
    var detailsExpanded by remember(item.callId) { mutableStateOf(false) }
    val handPointer = remember { PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)) }
    val scope = rememberCoroutineScope()
    val statusIcon =
        when (item.status) {
            ToolExecutionStatus.EXECUTING -> ChatAppIcons.ToolStatus.running
            ToolExecutionStatus.SUCCEEDED -> ChatAppIcons.ToolStatus.success
            ToolExecutionStatus.FAILED -> ChatAppIcons.ToolStatus.failed
        }
    val filePath = item.filePath
    val detailText = item.detailText
    val fileName = filePath?.substringAfterLast('/')?.substringAfterLast('\\')
    val linkDisplayName = filePath?.let(::compactPathForLink)
    val hasFileLink = !filePath.isNullOrBlank() && !fileName.isNullOrBlank() && item.displayText.contains(fileName)
    val displayPrefix = if (hasFileLink) item.displayText.substringBefore(fileName) else item.displayText
    val density = LocalDensity.current
    Box {
        if (statusHovered && !detailText.isNullOrBlank()) {
            val lineCount = detailText.lineSequence().count().coerceAtLeast(1)
            val tooltipOffsetY = with(density) { -((lineCount.coerceAtMost(20) * 18) + 22) }
            val tooltipOffsetX = with(density) { 18.dp.toPx().toInt() }
            Popup(offset = IntOffset(tooltipOffsetX, tooltipOffsetY)) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .background(Color(0xFF2B2B2B), RoundedCornerShape(6.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = detailText,
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp, color = Color.White),
                    )
                }
            }
        }
        if (hovered && !filePath.isNullOrBlank()) {
            val fileTooltipOffsetX = with(density) { 18.dp.toPx().toInt() }
            val fileTooltipOffsetY = with(density) { (-36).dp.toPx().toInt() }
            Popup(offset = IntOffset(fileTooltipOffsetX, fileTooltipOffsetY)) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .background(Color(0xFF2B2B2B), RoundedCornerShape(6.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = filePath,
                        style = JewelTheme.defaultTextStyle.copy(fontSize = 11.sp, color = Color.White),
                    )
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .pointerMoveFilter(
                            onEnter = {
                                statusHovered = true
                                false
                            },
                            onExit = {
                                statusHovered = false
                                false
                            },
                        ),
                ) {
                    Icon(
                        key = statusIcon,
                        contentDescription = item.status.name,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (hasFileLink) {
                        Text(
                            text = displayPrefix,
                            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        )
                        Box(
                            modifier = Modifier
                                .pointerHoverIcon(handPointer)
                                .pointerMoveFilter(
                                    onEnter = {
                                        hovered = true
                                        false
                                    },
                                    onExit = {
                                        hovered = false
                                        false
                                    },
                                )
                                .clickable {
                                    frontendLinkLog(
                                        project,
                                        FrontendLogLevel.INFO,
                                        "ToolExecutionRow.click filePath=$filePath"
                                    )
                                    scope.launch {
                                        runCatching {
                                            durable {
                                                QuantaBackendApi.getInstance()
                                                    .openProjectFile(project.projectId(), filePath!!)
                                            }
                                        }.onFailure { error ->
                                            frontendLinkLog(
                                                project,
                                                FrontendLogLevel.ERROR,
                                                "ToolExecutionRow.openProjectFile failed: ${error.message}"
                                            )
                                        }
                                    }
                                },
                        ) {
                            Text(
                                text = linkDisplayName ?: fileName,
                                style = JewelTheme.defaultTextStyle.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF69B7FF),
                                    textDecoration = TextDecoration.Underline,
                                ),
                            )
                        }
                    } else {
                        Text(
                            text = item.displayText,
                            style = JewelTheme.defaultTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        )
                    }
                }
                if (!detailText.isNullOrBlank()) {
                    IconButton(onClick = { detailsExpanded = !detailsExpanded }) {
                        Icon(
                            key = ChatAppIcons.ToolStatus.details,
                            contentDescription = if (detailsExpanded) "Hide details" else "Show details",
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            val secondary =
                when {
                    item.status == ToolExecutionStatus.FAILED && !item.errorText.isNullOrBlank() -> item.errorText
                    else -> null
                }
            if (!secondary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                SelectionContainer {
                    Text(
                        text = secondary,
                        style = JewelTheme.defaultTextStyle.copy(
                            fontSize = 11.sp,
                            color = ChatAppColors.Text.timestamp
                        ),
                    )
                }
            }
            if (!detailText.isNullOrBlank() && detailsExpanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(6.dp))
                        .padding(8.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = detailText,
                            style = JewelTheme.defaultTextStyle.copy(
                                fontSize = 11.sp,
                                color = ChatAppColors.Text.timestamp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun compactPathForLink(path: String): String {
    val normalized = path.replace('\\', '/').trim()
    if (normalized.length <= 40) return normalized
    val segments = normalized.split('/').filter { it.isNotBlank() }
    if (segments.isEmpty()) return normalized.takeLast(40)
    if (segments.size <= 2) return normalized
    val fileName = segments.last()
    val prev = segments.dropLast(1).lastOrNull().orEmpty()
    val tail = if (prev.isNotBlank()) "$prev/$fileName" else fileName
    if (tail.length <= 40) return tail
    val candidate = if (prev.isNotBlank()) "...$prev/$fileName" else "...$fileName"
    return if (candidate.length <= 44) candidate else "...${normalized.takeLast(40)}"
}

private fun frontendLinkLog(
    project: Project,
    level: FrontendLogLevel,
    message: String,
) {
    when (level) {
        FrontendLogLevel.DEBUG -> QDLog.debug(logger) { message }
        FrontendLogLevel.INFO -> QDLog.info(logger) { message }
        FrontendLogLevel.WARN -> QDLog.warn(logger) { message }
        FrontendLogLevel.ERROR -> QDLog.error(logger, { message }, null)
    }
    linkLogScope.launch {
        runCatching {
            QuantaBackendApi.getInstance().logFrontend(
                project.projectId(),
                FrontendLogDto(level = level, message = message),
            )
        }
    }
}


@Composable
private fun ThinkingMessageRow(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypingIndicator(
            modifier = Modifier.wrapContentSize(),
            color = ChatAppColors.Text.timestamp,
        )
    }
}

@Composable
private fun MessageHeader(message: ChatMessage) {
    val displayAuthor = if (message.isMyMessage) "Me" else message.author
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = displayAuthor,
            style = JewelTheme.defaultTextStyle.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = ChatAppColors.Text.authorName,
            ),
        )
        Text(
            text = message.formattedTime(),
            style = JewelTheme.defaultTextStyle.copy(
                fontSize = 11.sp,
                color = ChatAppColors.Text.timestamp,
            ),
        )
    }
}

@Composable
private fun MessageContent(message: ChatMessage) {
    Text(
        text = message.content,
        style = JewelTheme.defaultTextStyle.copy(
            fontSize = 13.sp,
            lineHeight = 18.sp,
        ),
    )
}

@Composable
private fun Modifier.messageBorder(
    shape: Shape,
    isMyMessage: Boolean,
    isHighlightedInSearch: Boolean,
    isMatchingSearch: Boolean,
): Modifier =
    border(
        width = when {
            isHighlightedInSearch -> 2.dp
            isMatchingSearch -> 1.5.dp
            else -> 1.dp
        },
        color =
            when {
                isHighlightedInSearch -> ChatAppColors.MessageBubble.searchHighlightedBackgroundBorder
                isMatchingSearch && isMyMessage -> ChatAppColors.MessageBubble.matchingMyBorder
                isMatchingSearch && !isMyMessage -> ChatAppColors.MessageBubble.matchingOthersBorder
                isMyMessage -> ChatAppColors.MessageBubble.myBackgroundBorder
                else -> ChatAppColors.MessageBubble.othersBackgroundBorder
            },
        shape = shape,
    )
