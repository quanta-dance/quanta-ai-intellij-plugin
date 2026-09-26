// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatHistoryPolicyTest {
    @Test
    fun `keeps user and manager messages in the linear model history`() {
        assertTrue(
            isMainConversationHistoryMessage(
                ChatMessage(
                    content = "request",
                    author = "Me",
                    isMyMessage = true,
                ),
            ),
        )
        assertTrue(isMainConversationHistoryMessage(ChatMessage(content = "response", author = "Quanta AI")))
    }

    @Test
    fun `excludes thinking tool and delegated-agent activity from model history`() {
        assertFalse(
            isMainConversationHistoryMessage(
                ChatMessage(
                    content = "AI is thinking…",
                    author = "AI Manager",
                    type = ChatMessage.ChatMessageType.AI_THINKING,
                ),
            ),
        )
        assertFalse(
            isMainConversationHistoryMessage(
                ChatMessage(content = "delegated result", author = "Developer"),
            ),
        )
        assertFalse(
            isMainConversationHistoryMessage(
                ChatMessage(
                    content = "",
                    author = "Quanta AI",
                    type = ChatMessage.ChatMessageType.TOOL,
                ),
            ),
        )
    }
}
