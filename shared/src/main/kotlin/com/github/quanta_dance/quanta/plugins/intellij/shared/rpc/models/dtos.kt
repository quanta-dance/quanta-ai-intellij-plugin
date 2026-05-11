// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import com.github.quanta_dance.quanta.plugins.intellij.shared.LocalDateTimeSerializer
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

@Serializable
data class ChatMessageDto(
    val id: String,
    val content: String,
    val author: String,
    val isMyMessage: Boolean,
    @Serializable(with = LocalDateTimeSerializer::class)
    val timestamp: LocalDateTime,
    val type: ChatMessage.ChatMessageType,
    val voiceSummary: String? = null,
    val toolItems: List<com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem> = emptyList(),
    val parentMessageId: String? = null,
)

fun ChatMessageDto.toChatMessage(): ChatMessage {
    return ChatMessage(
        id = id,
        content = content,
        author = author,
        isMyMessage = isMyMessage,
        timestamp = timestamp,
        type = type,
        voiceSummary = voiceSummary,
        toolItems = toolItems,
        parentMessageId = parentMessageId,
    )
}

fun ChatMessage.toChatMessageDto(): ChatMessageDto {
    return ChatMessageDto(
        id = id,
        content = content,
        author = author,
        isMyMessage = isMyMessage,
        timestamp = timestamp,
        type = type,
        voiceSummary = voiceSummary,
        toolItems = toolItems,
        parentMessageId = parentMessageId,
    )
}

@Serializable
data class ApplyRefactorSuggestionResultDto(
    val applied: Boolean,
    val newStartLine: Int? = null,
    val newEndLine: Int? = null,
    val errorMessage: String? = null,
)
