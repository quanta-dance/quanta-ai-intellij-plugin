// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.AgentPostMessageTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.AgentReadInboxTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.CancelAcpDelegationTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.DelegateTeamTaskTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.DelegateToAcpAgentTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.GetAcpDelegationStatusTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.SendAcpDelegationMessageTool
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagerToolPolicyTest {
    @Test
    fun `manager cannot access agent to agent inbox tools`() {
        assertFalse(ManagerToolPolicy.allows(AgentPostMessageTool::class.java))
        assertFalse(ManagerToolPolicy.allows(AgentReadInboxTool::class.java))
    }

    @Test
    fun `manager cannot access legacy ACP transport tools`() {
        assertFalse(ManagerToolPolicy.allows(DelegateToAcpAgentTool::class.java))
        assertFalse(ManagerToolPolicy.allows(GetAcpDelegationStatusTool::class.java))
        assertFalse(ManagerToolPolicy.allows(SendAcpDelegationMessageTool::class.java))
        assertFalse(ManagerToolPolicy.allows(CancelAcpDelegationTool::class.java))
    }

    @Test
    fun `manager can delegate tracked team work`() {
        assertTrue(ManagerToolPolicy.allows(DelegateTeamTaskTool::class.java))
    }
}
