// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatStateRefreshTargetsTest {
    @Test
    fun `does not request optional snapshots while standard chat is visible`() {
        val targets =
            ChatStateRefreshTargets.forUi(
                agenticEnabled = false,
                agenticTeamDialogVisible = false,
                mcpToolsDialogVisible = false,
            )

        assertFalse(targets.agenticState)
        assertFalse(targets.mcpStatus)
    }

    @Test
    fun `requests agent state while agentic mode or its dialog is visible`() {
        assertTrue(
            ChatStateRefreshTargets
                .forUi(
                    agenticEnabled = true,
                    agenticTeamDialogVisible = false,
                    mcpToolsDialogVisible = false,
                ).agenticState,
        )
        assertTrue(
            ChatStateRefreshTargets
                .forUi(
                    agenticEnabled = false,
                    agenticTeamDialogVisible = true,
                    mcpToolsDialogVisible = false,
                ).agenticState,
        )
    }

    @Test
    fun `requests detailed MCP status only while its dialog is visible`() {
        assertTrue(
            ChatStateRefreshTargets
                .forUi(
                    agenticEnabled = false,
                    agenticTeamDialogVisible = false,
                    mcpToolsDialogVisible = true,
                ).mcpStatus,
        )
    }
}
