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

    @Test
    fun `removes stale Quanta ACP permissions from every chat session`() {
        val service = ChatConversationStateService()
        service.loadState(
            ChatConversationStateService.State(
                activeSessionId = "active",
                sessions =
                    mutableListOf(
                        ChatConversationStateService.PersistedChatSession(
                            id = "active",
                            allowedAcpAgentIds = mutableListOf("quanta:stale", "codex"),
                        ),
                        ChatConversationStateService.PersistedChatSession(
                            id = "older",
                            allowedAcpAgentIds = mutableListOf("quanta:stale", "opencode"),
                        ),
                    ),
            ),
        )

        assertTrue(service.removeAcpAgentsMatching { agentId -> agentId.startsWith("quanta:") })

        val sessions = service.getState().sessions
        assertEquals(listOf("codex"), sessions[0].allowedAcpAgentIds)
        assertEquals(listOf("opencode"), sessions[1].allowedAcpAgentIds)
    }

    @Test
    fun `session activation and deletion keep exactly one valid active session`() {
        val service = ChatConversationStateService()
        val first = ChatConversationStateService.PersistedChatSession(id = "first", updatedAtEpochMs = 1)
        val second = ChatConversationStateService.PersistedChatSession(id = "second", updatedAtEpochMs = 2)
        service.loadState(
            ChatConversationStateService.State(
                activeSessionId = first.id,
                sessions = mutableListOf(first, second),
            ),
        )

        assertTrue(service.activateSession(second.id))
        assertEquals(second.id, service.getActiveSessionId())
        assertEquals(first.id, service.deleteSession(second.id))
        assertEquals(first.id, service.getActiveSessionId())
        assertFalse(service.activateSession("missing"))
        assertEquals(listOf(first.id), service.listSessions().filter { it.isActive }.map { it.id })
    }
}
