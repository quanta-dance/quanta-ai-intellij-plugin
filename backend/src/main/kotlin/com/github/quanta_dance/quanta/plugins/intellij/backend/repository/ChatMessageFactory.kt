// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.repository

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import java.time.LocalDateTime

/**
 * Factory class responsible for creating instances of `ChatMessage`.
 *
 * @param aiCompanionName The name of the AI companion, used as the author for AI-generated messages.
 * @param myUserName The name of the user, used as the author for user-generated messages.
 */
class ChatMessageFactory(
    private val aiCompanionName: String,
    private val myUserName: String,
) {

    /**
     * Creates a new instance of `ChatMessage` representing an AI-generated message response.
     *
     * @param content The content of the message.
     * @param timestamp The timestamp of the message. Defaults to the current time.
     */
    fun createAIMessage(
        content: String,
        timestamp: LocalDateTime = LocalDateTime.now(),
        voiceSummary: String? = null,
        toolItems: List<ToolExecutionItem> = emptyList(),
        parentMessageId: String? = null,
    ): ChatMessage =
        ChatMessage(
            content = content,
            author = aiCompanionName,
            timestamp = timestamp,
            isMyMessage = false,
            type = ChatMessage.ChatMessageType.TEXT,
            voiceSummary = voiceSummary,
            toolItems = toolItems,
            parentMessageId = parentMessageId,
        )

    fun createAIToolMessage(
        toolItems: List<ToolExecutionItem>,
        timestamp: LocalDateTime = LocalDateTime.now(),
        parentMessageId: String? = null,
    ): ChatMessage =
        ChatMessage(
            content = "",
            author = aiCompanionName,
            timestamp = timestamp,
            isMyMessage = false,
            type = ChatMessage.ChatMessageType.TOOL,
            toolItems = toolItems,
            parentMessageId = parentMessageId,
        )

    /**
     * Creates a new instance of `ChatMessage` representing a user message.
     *
     * @param content The content of the message.
     * @param timestamp The timestamp of the message. Defaults to the current time.
     */
    fun createUserMessage(
        content: String,
        timestamp: LocalDateTime = LocalDateTime.now(),
        sanitizedForAiContent: String? = null,
    ): ChatMessage =
        ChatMessage(
            content = content,
            author = myUserName,
            timestamp = timestamp,
            isMyMessage = true,
            type = ChatMessage.ChatMessageType.TEXT,
            sanitizedForAiContent = sanitizedForAiContent,
        )

}
