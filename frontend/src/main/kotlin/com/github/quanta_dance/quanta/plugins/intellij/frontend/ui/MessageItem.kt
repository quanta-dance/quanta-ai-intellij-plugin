package com.github.quanta_dance.quanta.plugins.intellij.frontend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.ChatAppColors
import com.github.quanta_dance.quanta.plugins.intellij.frontend.components.TypingIndicator
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

@Composable
fun MessageBubble(
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
        SelectionContainer {
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
                MessageContent(message)
            }
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message.formattedTime(),
            style = JewelTheme.editorTextStyle.copy(fontSize = 10.sp),
            color = ChatAppColors.Text.timestamp,
        )

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = if (message.isMyMessage) "Me" else message.author,
            style =
                JewelTheme.defaultTextStyle.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = ChatAppColors.Text.authorName,
                ),
        )
    }
}

@Composable
private fun MessageContent(message: ChatMessage) {
    Text(
        text = message.content,
        style =
            JewelTheme.defaultTextStyle.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = ChatAppColors.Text.normal,
                lineHeight = 17.sp,
            ),
    )
}

@Composable
private fun Modifier.messageBorder(
    shape: Shape,
    isMyMessage: Boolean,
    isHighlightedInSearch: Boolean,
    isMatchingSearch: Boolean,
) =
    border(
        width = if (isMyMessage) 0.dp else 1.dp,
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
