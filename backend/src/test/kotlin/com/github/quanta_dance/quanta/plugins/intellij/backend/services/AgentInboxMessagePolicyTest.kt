// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentInboxMessagePolicyTest {
    @Test
    fun `roster updates are suppressed without waking an agent`() {
        assertFalse(AgentInboxMessagePolicy.shouldDeliver("roster_update"))
        assertFalse(AgentInboxMessagePolicy.shouldDeliver("ROSTER_UPDATE"))
    }

    @Test
    fun `actionable inbox messages remain deliverable`() {
        assertTrue(AgentInboxMessagePolicy.shouldDeliver(null))
        assertTrue(AgentInboxMessagePolicy.shouldDeliver("notification"))
        assertTrue(AgentInboxMessagePolicy.shouldDeliver("task_request"))
    }
}
