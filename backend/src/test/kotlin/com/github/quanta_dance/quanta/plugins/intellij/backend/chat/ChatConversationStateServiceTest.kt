// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.DelegatedTaskStatusDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatConversationStateServiceTest {
    @Test
    fun `restart recovery removes transient thinking and stops interrupted work`() {
        val service = ChatConversationStateService()
        val session =
            ChatConversationStateService.PersistedChatSession(
                id = "session",
                messages =
                    mutableListOf(
                        ChatConversationStateService.PersistedChatMessage(
                            id = "thinking",
                            content = "AI is thinking…",
                            type = ChatMessage.ChatMessageType.AI_THINKING.name,
                        ),
                        ChatConversationStateService.PersistedChatMessage(
                            id = "tool",
                            content = "",
                            type = ChatMessage.ChatMessageType.TOOL.name,
                            toolItems =
                                mutableListOf(
                                    ChatConversationStateService.PersistedToolItem(
                                        callId = "call",
                                        status = ToolExecutionStatus.EXECUTING.name,
                                    ),
                                ),
                        ),
                    ),
                delegatedTasks =
                    mutableListOf(
                        ChatConversationStateService.PersistedDelegatedTask(
                            id = "running",
                            status = DelegatedTaskStatusDto.RUNNING.name,
                        ),
                        ChatConversationStateService.PersistedDelegatedTask(
                            id = "queued",
                            status = DelegatedTaskStatusDto.QUEUED.name,
                        ),
                    ),
            )

        service.loadState(
            ChatConversationStateService.State(
                activeSessionId = session.id,
                sessions = mutableListOf(session),
            ),
        )

        val recovered = service.getState().sessions.single()
        assertFalse(recovered.messages.any { it.type == ChatMessage.ChatMessageType.AI_THINKING.name })
        assertEquals(
            ToolExecutionStatus.FAILED.name,
            recovered.messages
                .single()
                .toolItems
                .single()
                .status,
        )
        assertEquals(
            "Interrupted by IDE restart",
            recovered.messages
                .single()
                .toolItems
                .single()
                .errorText,
        )
        assertEquals(DelegatedTaskStatusDto.FAILED.name, recovered.delegatedTasks[0].status)
        assertEquals("Interrupted by IDE restart", recovered.delegatedTasks[0].summary)
        assertEquals(DelegatedTaskStatusDto.QUEUED.name, recovered.delegatedTasks[1].status)
    }

    @Test
    fun `does not persist transient thinking indicators`() {
        val service = ChatConversationStateService()
        service.loadState(ChatConversationStateService.State())

        service.saveActiveMessages(
            messages =
                listOf(
                    ChatMessage(content = "request", author = "Me", isMyMessage = true),
                    ChatMessage(
                        content = "AI is thinking…",
                        author = "AI Manager",
                        type = ChatMessage.ChatMessageType.AI_THINKING,
                    ),
                    ChatMessage(content = "response", author = "Quanta AI"),
                ),
            lastResponseId = null,
        )

        assertTrue(
            service
                .getState()
                .sessions
                .single()
                .messages
                .none { it.type == ChatMessage.ChatMessageType.AI_THINKING.name },
        )
    }
}
