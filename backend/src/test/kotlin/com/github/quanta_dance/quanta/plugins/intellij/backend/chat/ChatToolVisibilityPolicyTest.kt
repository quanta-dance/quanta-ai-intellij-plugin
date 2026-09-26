// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatToolVisibilityPolicyTest {
    @Test
    fun `hides tools represented by dedicated collaboration UI`() {
        assertTrue(ChatToolVisibilityPolicy.shouldHideToolCard("DelegateTeamTaskTool"))
        assertTrue(ChatToolVisibilityPolicy.shouldHideToolCard("DelegateToAcpAgentTool"))
        assertTrue(ChatToolVisibilityPolicy.shouldHideToolCard("GetAcpDelegationStatusTool"))
    }

    @Test
    fun `keeps normal implementation tools visible`() {
        assertFalse(ChatToolVisibilityPolicy.shouldHideToolCard("SearchInFiles"))
        assertFalse(ChatToolVisibilityPolicy.shouldHideToolCard("PatchFile"))
    }
}
