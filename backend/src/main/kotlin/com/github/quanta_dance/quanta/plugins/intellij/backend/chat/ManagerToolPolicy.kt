// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.AgentPostMessageTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.AgentReadInboxTool

/** Keeps agent-to-agent inbox mechanics out of the manager's tool surface. */
internal object ManagerToolPolicy {
    private val agentOnlyTools =
        setOf(
            AgentPostMessageTool::class.java,
            AgentReadInboxTool::class.java,
        )

    fun allows(toolClass: Class<*>): Boolean = toolClass !in agentOnlyTools
}
