// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

/**
 * Identifies optional backend snapshots required by the currently visible chat UI.
 *
 * Message and session state remain part of every visible chat refresh. Agent/team and detailed MCP
 * snapshots are intentionally demand-driven because their controls live in opt-in UI surfaces.
 */
internal data class ChatStateRefreshTargets(
    val agenticState: Boolean,
    val mcpStatus: Boolean,
) {
    companion object {
        fun forUi(
            agenticEnabled: Boolean,
            agenticTeamDialogVisible: Boolean,
            mcpToolsDialogVisible: Boolean,
        ): ChatStateRefreshTargets =
            ChatStateRefreshTargets(
                agenticState = agenticEnabled || agenticTeamDialogVisible,
                mcpStatus = mcpToolsDialogVisible,
            )
    }
}
