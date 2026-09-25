// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

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
    @Volatile
    private var discoveredAgents: List<AcpAgentDto> = emptyList()

    fun refresh(): List<AcpAgentDto> {
        discoveredAgents =
            AcpAgentDiscoveryService(
                manualAgents = BackendRuntimeSettingsService.instance.settings.manualAcpAgents,
            ).discover()
        return agents()
    }

    fun agents(): List<AcpAgentDto> =
        (discoveredAgents + project.service<QuantaAcpShareService>().joinedAcpAgents())
            .distinctBy(AcpAgentDto::id)
            .sortedBy(AcpAgentDto::name)

    fun find(agentId: String): AcpAgentDto? = agents().firstOrNull { it.id == agentId }
}
