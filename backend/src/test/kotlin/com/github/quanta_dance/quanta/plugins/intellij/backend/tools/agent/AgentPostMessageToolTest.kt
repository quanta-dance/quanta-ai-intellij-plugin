// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentPostMessageToolTest {
    @Test
    fun `defaults to an agent inbox destination`() {
        assertEquals(AgentMessageDestination.AGENT, AgentPostMessageTool().destination)
    }

    @Test
    fun `supports explicit manager reports separately from agent inbox messages`() {
        val tool = AgentPostMessageTool()
        tool.destination = AgentMessageDestination.MANAGER

        assertEquals(AgentMessageDestination.MANAGER, tool.destination)
    }

    @Test
    fun `supports tracked agent task requests separately from notifications`() {
        val tool = AgentPostMessageTool()
        tool.destination = AgentMessageDestination.AGENT_TASK

        assertEquals(AgentMessageDestination.AGENT_TASK, tool.destination)
    }
}
