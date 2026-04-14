// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AgentManagerServiceDefaultTeamTest : BasePlatformTestCase() {
    fun testCreateDefaultTeamCreatesThreeAgentsAndPersistsThem() {
        val svc = project.service<AgentManagerService>()
        val ids = svc.createDefaultTeam()
        assertEquals(3, ids.size)

        val snaps = svc.getAgentsSnapshot()
        assertEquals(3, snaps.size)
        val roles = snaps.map { it.role }.toSet()
        assertTrue(roles.contains("Developer Agent"))
        assertTrue(roles.contains("Test Agent"))
        assertTrue(roles.contains("Project Analyst"))

        val st = FrontendQuantaSettingsState.instance.state
        assertEquals(3, st.agents.size)
    }

    fun testCreateDefaultTeamIsIdempotent() {
        val svc = project.service<AgentManagerService>()
        val ids1 = svc.createDefaultTeam()
        val ids2 = svc.createDefaultTeam()
        assertTrue(ids1.isNotEmpty())
        assertTrue(ids2.isNotEmpty())
        assertEquals(3, svc.getAgentsSnapshot().size)
    }

    fun testRosterUpdateIsPostedToInboxes() {
        val svc = project.service<AgentManagerService>()
        val ids = svc.createDefaultTeam()
        val st = FrontendQuantaSettingsState.instance.state
        ids.forEach { id ->
            val inbox = st.agentInboxes[id]
            assertNotNull(inbox)
            assertTrue(inbox!!.any { it.kind == "roster_update" })
        }
    }

    fun testDefaultTeamDoesNotAllowReadInboxToolForAgents() {
        val svc = project.service<AgentManagerService>()
        val ids = svc.createDefaultTeam()
        ids.forEach { id ->
            val allowed = svc.getAgentAllowedBuiltInNames(id)
            assertNotNull(allowed)
            assertTrue(!allowed!!.contains("AgentReadInboxTool"))
        }
    }
}
