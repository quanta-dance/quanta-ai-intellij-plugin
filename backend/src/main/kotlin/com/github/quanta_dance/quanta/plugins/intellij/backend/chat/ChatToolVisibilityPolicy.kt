// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

/** Hides low-level coordination calls that already have dedicated task or collaboration UI. */
internal object ChatToolVisibilityPolicy {
    private val dedicatedUiTools =
        setOf(
            "DelegateTeamTaskTool",
            "DelegateToAcpAgentTool",
            "GetAcpDelegationStatusTool",
            "SendAcpDelegationMessageTool",
            "CancelAcpDelegationTool",
        )

    fun shouldHideToolCard(toolName: String): Boolean = toolName in dedicatedUiTools
}
