// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the most recently verified ACP roster for one project.
 *
 * Refreshing the roster is an explicit UI operation because probing local applications can be slow.
 * Agent tools only use this cache and the active Quanta shares; they never launch discovery by
 * themselves.
 */
@Service(Service.Level.PROJECT)
class AcpAgentRosterService(
    private val project: Project,
) {
    private val refreshScheduled = AtomicBoolean(false)

    @Volatile
    private var discoveredAgents: List<AcpAgentDto> = project.service<AcpAgentRosterStateService>().agents()

    @Synchronized
    fun refresh(): List<AcpAgentDto> {
        try {
            discoveredAgents =
                AcpAgentDiscoveryService(
                    manualAgents = BackendRuntimeSettingsService.instance.settings.manualAcpAgents,
                ).discover()
            project.service<AcpAgentRosterStateService>().replace(discoveredAgents)
            return agents(scheduleRefresh = false)
        } finally {
            refreshScheduled.set(false)
        }
    }

    fun agents(): List<AcpAgentDto> = agents(scheduleRefresh = true)

    private fun agents(scheduleRefresh: Boolean): List<AcpAgentDto> {
        if (scheduleRefresh) scheduleBackgroundRefresh()
        val joinedAgents = project.service<QuantaAcpShareService>().joinedAcpAgents()
        return (
            discoveredAgents.filterNot(::isQuantaAcpAgent) + joinedAgents
        ).distinctBy(::acpRosterIdentity)
            .sortedBy(AcpAgentDto::name)
    }

    private fun scheduleBackgroundRefresh() {
        if (!refreshScheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread { refresh() }
    }

    fun find(agentId: String): AcpAgentDto? = agents().firstOrNull { it.id == agentId }
}

internal fun acpRosterIdentity(agent: AcpAgentDto): String = agent.peerIdentity?.let { "quanta:$it" } ?: agent.id

private fun isQuantaAcpAgent(agent: AcpAgentDto): Boolean =
    agent.command == QUANTA_ACP_COMMAND || agent.id.startsWith(QUANTA_ACP_AGENT_ID_PREFIX)

private const val QUANTA_ACP_AGENT_ID_PREFIX = "quanta:"
private val QUANTA_ACP_COMMAND = listOf("quanta-acp")
