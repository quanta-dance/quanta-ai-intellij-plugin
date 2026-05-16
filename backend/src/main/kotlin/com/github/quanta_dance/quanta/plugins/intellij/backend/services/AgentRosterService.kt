// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentInfoDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.beans.PropertyChangeListener

@Service(Service.Level.PROJECT)
class AgentRosterService(
    project: Project,
) {
    private val agentManager = project.service<AgentManagerService>()
    private val _agentsFlow = MutableStateFlow(currentAgents())
    val agentsFlow: StateFlow<List<AgentInfoDto>> = _agentsFlow.asStateFlow()

    private val agentListener =
        PropertyChangeListener { event ->
            if (event.propertyName == "agents") {
                _agentsFlow.value = currentAgents()
            }
        }

    init {
        agentManager.addPropertyChangeListener(agentListener)
        _agentsFlow.value = currentAgents()
    }

    private fun currentAgents(): List<AgentInfoDto> {
        agentManager.ensureAgentsLoadedFromSession()
        return agentManager.getAgentsSnapshot().map {
            AgentInfoDto(
                id = it.id,
                role = it.role,
                model = it.model?.takeIf { model -> model.isNotBlank() }
                    ?: BackendRuntimeSettingsService.instance.settings.aiChatModel,
                instructions = it.instructions,
            )
        }
    }
}
