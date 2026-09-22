// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

import com.github.quanta_dance.quanta.plugins.intellij.frontend.rpc.FrontendSettingsRpcService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.rpc.rpcProjectPath
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendMcpConfigService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.toDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.ChatRepositoryRpcApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentChannelEventDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentInfoDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatPlanStatusDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatSessionDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.DelegatedTaskDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.McpServerStatusDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.toChatMessage
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private val _acpAgentsFlow = MutableStateFlow<List<AcpAgentDto>>(emptyList())
    override val acpAgentsFlow: StateFlow<List<AcpAgentDto>> = _acpAgentsFlow.asStateFlow()

    private val _acpDiscoveryLoadingFlow = MutableStateFlow(false)
    override val acpDiscoveryLoadingFlow: StateFlow<Boolean> = _acpDiscoveryLoadingFlow.asStateFlow()

    private val _allowedAcpAgentIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    override val allowedAcpAgentIdsFlow: StateFlow<Set<String>> = _allowedAcpAgentIdsFlow.asStateFlow()

    private val _mcpServersFlow = MutableStateFlow<List<McpServerStatusDto>>(emptyList())
    override val mcpServersFlow: StateFlow<List<McpServerStatusDto>> = _mcpServersFlow.asStateFlow()

    private val _mcpConfigurationLoadingFlow = MutableStateFlow(true)
    override val mcpConfigurationLoadingFlow: StateFlow<Boolean> = _mcpConfigurationLoadingFlow.asStateFlow()

    private val _mcpConfigurationErrorFlow = MutableStateFlow<String?>(null)
    override val mcpConfigurationErrorFlow: StateFlow<String?> = _mcpConfigurationErrorFlow.asStateFlow()

    private val _delegatedTasksFlow = MutableStateFlow<List<DelegatedTaskDto>>(emptyList())
    override val delegatedTasksFlow: StateFlow<List<DelegatedTaskDto>> = _delegatedTasksFlow.asStateFlow()

    private val _channelEventsFlow = MutableStateFlow<List<AgentChannelEventDto>>(emptyList())
    override val channelEventsFlow: StateFlow<List<AgentChannelEventDto>> = _channelEventsFlow.asStateFlow()

    private val acpDiscoveryMutex = Mutex()

    init {
        coroutineScope.launch {
            initializeCurrentChat()
            launch { refreshAllowedAcpAgentsIfNeeded() }
            pollCurrentState()
        }
    }

    /** Synchronizes the backend and restores the active chat's persisted state after IDE startup. */
    private suspend fun initializeCurrentChat() {
        runCatching { syncSettingsToBackend() }
            .onFailure { error ->
                logger.warn(
                    "Failed to synchronize settings before restoring ACP availability",
                    error,
                )
            }
        refreshCurrentState()
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
        val projectPath = project.rpcProjectPath()

        runCatching {
            _messagesFlow.value = chatApi.getCurrentMessages(projectPath).map { it.toChatMessage() }
        }.onFailure { error ->
            logger.warn("Failed to refresh current messages from backend", error)
        }
        runCatching {
            _sessionsFlow.value = chatApi.getCurrentSessions(projectPath)
            _allowedAcpAgentIdsFlow.value = chatApi.getAllowedAcpAgentIds(projectPath).toSet()
        }.onFailure { error ->
            logger.warn("Failed to refresh current sessions from backend", error)
        }
        runCatching {
            val mcpStatuses = backendApi.getMcpServerStatuses(projectPath)
            _mcpServersFlow.value = mcpStatuses.servers
            _mcpConfigurationLoadingFlow.value = mcpStatuses.configurationLoading
            _mcpConfigurationErrorFlow.value = mcpStatuses.configurationError
        }.onFailure { error ->
            _mcpConfigurationLoadingFlow.value = true
            logger.warn("Failed to refresh MCP server statuses from backend", error)
        }
        runCatching {
            _planStatusFlow.value = backendApi.getCurrentPlanStatus(projectPath)
        }.onFailure { error ->
            logger.warn("Failed to refresh current plan status from backend", error)
        }
        runCatching {
            _agentsFlow.value = backendApi.getCurrentAgents(projectPath)
        }.onFailure { error ->
            logger.warn("Failed to refresh current agents from backend", error)
        }
        runCatching {
            _delegatedTasksFlow.value = backendApi.getCurrentDelegatedTasks(projectPath)
        }.onFailure { error ->
            logger.warn("Failed to refresh current delegated tasks from backend", error)
        }
        runCatching {
            _channelEventsFlow.value = backendApi.getCurrentChannelEvents(projectPath)
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
        syncSettingsToBackend()
        ChatRepositoryRpcApi
            .getInstance()
            .sendMessage(project.rpcProjectPath(), messageContent)
        refreshCurrentState()
    }

    override suspend fun createNewSession() {
        ChatRepositoryRpcApi.getInstance().createNewSession(project.rpcProjectPath())
        refreshCurrentState()
    }

    override suspend fun activateSession(sessionId: String) {
        ChatRepositoryRpcApi.getInstance().activateSession(project.rpcProjectPath(), sessionId)
        refreshCurrentState()
        refreshAllowedAcpAgentsIfNeeded()
    }

    override suspend fun deleteSession(sessionId: String) {
        ChatRepositoryRpcApi.getInstance().deleteSession(project.rpcProjectPath(), sessionId)
        refreshCurrentState()
    }

    override suspend fun setAgenticMode(enabled: Boolean) {
        FrontendQuantaSettingsState.instance.state.agenticEnabled = enabled
        syncSettingsToBackend()
        refreshCurrentState()
        if (enabled) refreshAllowedAcpAgentsIfNeeded()
    }

    override suspend fun refreshAcpAgents() {
        discoverAcpAgents()
    }

    private suspend fun refreshAllowedAcpAgentsIfNeeded() {
        if (FrontendQuantaSettingsState.instance.state.agenticEnabled == false) return

        val allowedAgentIds = _allowedAcpAgentIdsFlow.value
        val discoveredAgentIds = _acpAgentsFlow.value.mapTo(mutableSetOf(), AcpAgentDto::id)
        if (allowedAgentIds.isEmpty() || allowedAgentIds.all(discoveredAgentIds::contains)) return

        discoverAcpAgents()
    }

    private suspend fun discoverAcpAgents() =
        acpDiscoveryMutex.withLock {
            _acpDiscoveryLoadingFlow.value = true
            try {
                syncSettingsToBackend()
                _acpAgentsFlow.value = QuantaBackendApi.getInstance().discoverAcpAgents()
                _allowedAcpAgentIdsFlow.value =
                    ChatRepositoryRpcApi.getInstance().getAllowedAcpAgentIds(project.rpcProjectPath()).toSet()
            } catch (error: Exception) {
                logger.warn("Failed to discover ACP agents", error)
            } finally {
                _acpDiscoveryLoadingFlow.value = false
            }
        }

    override suspend fun setAcpAgentAllowed(
        agentId: String,
        allowed: Boolean,
    ) {
        ChatRepositoryRpcApi.getInstance().setAcpAgentAllowed(project.rpcProjectPath(), agentId, allowed)
        _allowedAcpAgentIdsFlow.value =
            ChatRepositoryRpcApi.getInstance().getAllowedAcpAgentIds(project.rpcProjectPath()).toSet()
        refreshCurrentState()
    }

    override suspend fun setMcpServerEnabled(
        serverName: String,
        enabled: Boolean,
    ) {
        ChatRepositoryRpcApi.getInstance().setMcpServerEnabled(project.rpcProjectPath(), serverName, enabled)
        refreshCurrentState()
    }

    override suspend fun retryMcpServerConnection(serverName: String) {
        QuantaBackendApi.getInstance().retryMcpServerConnection(project.rpcProjectPath(), serverName)
        refreshCurrentState()
    }

    private suspend fun syncSettingsToBackend() {
        val settings = FrontendQuantaSettingsState.instance.state
        val mcpServersJson =
            checkNotNull(project.service<FrontendMcpConfigService>().readForSync()) {
                "MCP config is empty or unreadable"
            }
        FrontendSettingsRpcService.getInstance(project).updateSettings(settings.toDto(project, mcpServersJson))
    }

    override suspend fun createDefaultAgentTeam() {
        QuantaBackendApi.getInstance().createDefaultAgentTeam(project.rpcProjectPath())
        refreshCurrentState()
    }

    override suspend fun stopAllAgents(): Int {
        val stopped = ChatRepositoryRpcApi.getInstance().stopAllAgents(project.rpcProjectPath())
        refreshCurrentState()
        return stopped
    }
}
