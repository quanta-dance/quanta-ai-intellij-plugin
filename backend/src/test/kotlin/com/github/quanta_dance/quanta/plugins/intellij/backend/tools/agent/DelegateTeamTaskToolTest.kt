// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AgentManagerService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DelegateTeamTaskToolTest {
    @Test
    fun `resolves every requested target before starting a team delegation`() {
        val resolution =
            resolveDelegationTargets(
                targets =
                    listOf(
                        DelegateTeamTaskTool.Target(agentId = "stale-developer-id", agentRole = "Developer"),
                        DelegateTeamTaskTool.Target(agentId = "missing-reviewer-id"),
                    ),
                agents =
                    listOf(
                        snapshot(id = "developer-id", role = "Developer"),
                        snapshot(id = "reviewer-id", role = "Reviewer"),
                    ),
            )

        assertEquals(listOf("developer-id"), resolution.resolved.map(AgentManagerService.AgentSnapshot::id))
        assertEquals(listOf("missing-reviewer-id"), resolution.unresolved)
    }

    @Test
    fun `deduplicates repeated resolved targets`() {
        val resolution =
            resolveDelegationTargets(
                targets =
                    listOf(
                        DelegateTeamTaskTool.Target(agentId = "developer-id", agentRole = "Developer"),
                        DelegateTeamTaskTool.Target(agentId = "stale-id", agentRole = "Developer"),
                    ),
                agents = listOf(snapshot(id = "developer-id", role = "Developer")),
            )

        assertEquals(listOf("developer-id"), resolution.resolved.map(AgentManagerService.AgentSnapshot::id))
        assertTrue(resolution.unresolved.isEmpty())
    }

    @Test
    fun `requires declared recipient count to match distinct targets`() {
        val targets =
            listOf(
                DelegateTeamTaskTool.Target(agentId = "developer-id", agentRole = "Developer"),
                DelegateTeamTaskTool.Target(agentId = "reviewer-id", agentRole = "Reviewer"),
            )

        assertTrue(hasExpectedTargetCount(expectedTargetCount = 2, targets))
        assertTrue(!hasExpectedTargetCount(expectedTargetCount = 3, targets))
        assertTrue(!hasExpectedTargetCount(expectedTargetCount = 0, targets))
    }

    @Test
    fun `uses a natural handoff message for a team delegation`() {
        assertEquals(
            "I asked Developer, Reviewer, and Tester to work independently. " +
                "I will summarize the findings after every report is ready.",
            teamDelegationHandoffMessage(listOf("Developer", "Reviewer", "Tester")),
        )
    }

    private fun snapshot(
        id: String,
        role: String,
    ) = AgentManagerService.AgentSnapshot(
        id = id,
        role = role,
        instructions = null,
        model = null,
        isWorking = false,
    )
}
