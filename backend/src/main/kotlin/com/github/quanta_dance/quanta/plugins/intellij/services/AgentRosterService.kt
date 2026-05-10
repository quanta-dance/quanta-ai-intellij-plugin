package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendQuantaSettingsState
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
    private val settings = project.service<BackendQuantaSettingsState>()
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

    private fun currentAgents(): List<AgentInfoDto> =
        agentManager.getAgentsSnapshot().map {
            AgentInfoDto(
                id = it.id,
                role = it.role,
                model = it.model?.takeIf { model -> model.isNotBlank() } ?: settings.state.aiChatModel,
                instructions = it.instructions,
            )
        }
}
