// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.contracts

import com.github.quanta_dance.quanta.plugins.intellij.shared.Searchable
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage.ChatMessageType.TEXT
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private val timeFormatter: DateTimeFormatter? = DateTimeFormatter.ofPattern("HH:mm")

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val author: String,
    val isMyMessage: Boolean = false,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val type: ChatMessageType = TEXT,
    val voiceSummary: String? = null,
    val toolItems: List<ToolExecutionItem> = emptyList(),
    val parentMessageId: String? = null,
) : Searchable {
    enum class ChatMessageType {
        AI_THINKING,
        TOOL,
        TEXT,
    }

    @JvmOverloads
    fun formattedTime(dateTimeFormatter: DateTimeFormatter? = timeFormatter): String = timestamp.format(dateTimeFormatter)

    fun isTextMessage(): Boolean = this.type == ChatMessageType.TEXT

    fun isAIThinkingMessage(): Boolean = this.type == ChatMessageType.AI_THINKING

    fun isToolMessage(): Boolean = this.type == ChatMessageType.TOOL

    override fun matches(query: String): Boolean {
        if (query.isBlank()) return false

        return content.contains(query, ignoreCase = true)
    }
}
