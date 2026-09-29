// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.AgentPostMessageTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.AgentReadInboxTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.CancelAcpDelegationTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.DelegateToAcpAgentTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.GetAcpDelegationStatusTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.SendAcpDelegationMessageTool

/** Keeps transport-specific collaboration mechanics out of the manager's tool surface. */
internal object ManagerToolPolicy {
    private val managerExcludedTools =
        setOf(
            AgentPostMessageTool::class.java,
            AgentReadInboxTool::class.java,
            DelegateToAcpAgentTool::class.java,
            GetAcpDelegationStatusTool::class.java,
            SendAcpDelegationMessageTool::class.java,
            CancelAcpDelegationTool::class.java,
        )

    fun allows(toolClass: Class<*>): Boolean = toolClass !in managerExcludedTools
}
