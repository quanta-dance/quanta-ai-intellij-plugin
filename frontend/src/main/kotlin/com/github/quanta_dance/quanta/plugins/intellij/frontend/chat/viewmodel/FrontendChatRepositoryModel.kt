// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

import com.github.quanta_dance.quanta.plugins.intellij.frontend.rpc.FrontendSettingsRpcService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendMcpConfigService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.toDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.ChatRepositoryRpcApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentChannelEventDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentInfoDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatPlanStatusDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.DelegatedTaskDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.toChatMessage
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Frontend-side view model adapter over the split-mode RPC layer.
 *
 * It keeps frontend state flows refreshed from backend snapshot RPC calls so the UI can stay
 * reactive without depending on Fleet RPC stream descriptors that fail plugin verification.
 */
@Service(Level.PROJECT)
class FrontendChatRepositoryModel(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : ChatRepositoryApi {
    private val logger = thisLogger()

    companion object {
        fun getInstance(project: Project): FrontendChatRepositoryModel = project.getService(FrontendChatRepositoryModel::class.java)
    }

    private val _messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messagesFlow: StateFlow<List<ChatMessage>> = _messagesFlow.asStateFlow()

    private val _sessionsFlow = MutableStateFlow<List<ChatSessionDto>>(emptyList())
    override val sessionsFlow: StateFlow<List<ChatSessionDto>> = _sessionsFlow.asStateFlow()

    private val _planStatusFlow = MutableStateFlow(ChatPlanStatusDto())
    override val planStatusFlow: StateFlow<ChatPlanStatusDto> = _planStatusFlow.asStateFlow()

    private val _agentsFlow = MutableStateFlow<List<AgentInfoDto>>(emptyList())
    override val agentsFlow: StateFlow<List<AgentInfoDto>> = _agentsFlow.asStateFlow()

    private val _delegatedTasksFlow = MutableStateFlow<List<DelegatedTaskDto>>(emptyList())
    override val delegatedTasksFlow: StateFlow<List<DelegatedTaskDto>> = _delegatedTasksFlow.asStateFlow()

    private val _channelEventsFlow = MutableStateFlow<List<AgentChannelEventDto>>(emptyList())
    override val channelEventsFlow: StateFlow<List<AgentChannelEventDto>> = _channelEventsFlow.asStateFlow()

    init {
        coroutineScope.launch { pollCurrentState() }
    }

    private suspend fun refreshCurrentState() {
        val chatApi =
            runCatching { ChatRepositoryRpcApi.getInstance() }
                .getOrElse { error ->
                    logger.warn("Failed to resolve chat RPC API", error)
                    return
                }
        val backendApi =
            runCatching { QuantaBackendApi.getInstance() }
                .getOrElse { error ->
                    logger.warn("Failed to resolve backend RPC API", error)
                    return
                }
        val projectId = project.projectId()

        runCatching {
            _messagesFlow.value = chatApi.getCurrentMessages(projectId).map { it.toChatMessage() }
        }.onFailure { error ->
            logger.warn("Failed to refresh current messages from backend", error)
        }
        runCatching {
            _sessionsFlow.value = chatApi.getCurrentSessions(projectId)
        }.onFailure { error ->
            logger.warn("Failed to refresh current sessions from backend", error)
        }
        runCatching {
            _planStatusFlow.value = backendApi.getCurrentPlanStatus(projectId)
        }.onFailure { error ->
            logger.warn("Failed to refresh current plan status from backend", error)
        }
        runCatching {
            _agentsFlow.value = backendApi.getCurrentAgents(projectId)
        }.onFailure { error ->
            logger.warn("Failed to refresh current agents from backend", error)
        }
        runCatching {
            _delegatedTasksFlow.value = backendApi.getCurrentDelegatedTasks(projectId)
        }.onFailure { error ->
            logger.warn("Failed to refresh current delegated tasks from backend", error)
        }
        runCatching {
            _channelEventsFlow.value = backendApi.getCurrentChannelEvents(projectId)
        }.onFailure { error ->
            logger.warn("Failed to refresh current channel events from backend", error)
        }
    }

    private suspend fun pollCurrentState() {
        while (true) {
            refreshCurrentState()
            delay(1_500)
        }
    }

    override suspend fun sendMessage(messageContent: String) {
        ChatRepositoryRpcApi
            .getInstance()
            .sendMessage(project.projectId(), messageContent)
        refreshCurrentState()
    }

    override suspend fun createNewSession() {
        ChatRepositoryRpcApi.getInstance().createNewSession(project.projectId())
        refreshCurrentState()
    }

    override suspend fun activateSession(sessionId: String) {
        ChatRepositoryRpcApi.getInstance().activateSession(project.projectId(), sessionId)
        refreshCurrentState()
    }

    override suspend fun deleteSession(sessionId: String) {
        ChatRepositoryRpcApi.getInstance().deleteSession(project.projectId(), sessionId)
        refreshCurrentState()
    }

    override suspend fun setAgenticMode(enabled: Boolean) {
        val settings = FrontendQuantaSettingsState.instance.state
        settings.agenticEnabled = enabled
        val mcpServersJson = project.service<FrontendMcpConfigService>().readForSync()
        FrontendSettingsRpcService.getInstance(project).updateSettings(settings.toDto(project, mcpServersJson!!))
        refreshCurrentState()
    }

    override suspend fun createDefaultAgentTeam() {
        QuantaBackendApi.getInstance().createDefaultAgentTeam(project.projectId())
        refreshCurrentState()
    }

    override suspend fun stopAllAgents(): Int {
        val stopped = ChatRepositoryRpcApi.getInstance().stopAllAgents(project.projectId())
        refreshCurrentState()
        return stopped
    }
}
