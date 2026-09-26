// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationIntentDto
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentPostMessageToolTest {
    @Test
    fun `defaults to a fire and forget collaboration notification`() {
        assertEquals(CollaborationIntentDto.NOTIFICATION, AgentPostMessageTool().intent)
    }

    @Test
    fun `uses one unified recipient identifier for task work`() {
        val tool = AgentPostMessageTool()
        tool.recipientId = "acp:orders-service"
        tool.intent = CollaborationIntentDto.TASK

        assertEquals("acp:orders-service", tool.recipientId)
        assertEquals(CollaborationIntentDto.TASK, tool.intent)
    }

    @Test
    fun `supports correlated result and status intents without a recipient type switch`() {
        val tool = AgentPostMessageTool()
        tool.recipientId = "manager:chat-1"

        tool.intent = CollaborationIntentDto.RESULT
        assertEquals(CollaborationIntentDto.RESULT, tool.intent)

        tool.intent = CollaborationIntentDto.STATUS
        assertEquals(CollaborationIntentDto.STATUS, tool.intent)
    }
}
