// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

import com.github.quanta_dance.quanta.plugins.intellij.frontend.rpc.FrontendSettingsRpcService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.toDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.ChatRepositoryRpcApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.*
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Frontend-side durable view model adapter over the split-mode RPC layer.
 *
 * It converts backend chat, plan, agent, and channel flows into frontend-consumable state flows so
 * the UI can remain reactive without owning backend execution logic.
 */
@Service(Level.PROJECT)
class FrontendChatRepositoryModel(
    private val project: Project,
    coroutineScope: CoroutineScope,
) : ChatRepositoryApi {
    companion object {
        fun getInstance(project: Project): FrontendChatRepositoryModel =
            project.getService(FrontendChatRepositoryModel::class.java)
    }

    override val messagesFlow: StateFlow<List<ChatMessage>> = flow {
        durable {
            ChatRepositoryRpcApi
                .getInstance()
                .getMessagesFlow(project.projectId())
                .collect { valueFromBackend ->
                    emit(valueFromBackend.map { messageDto -> messageDto.toChatMessage() })
                }
        }
    }.stateIn(coroutineScope, initialValue = emptyList(), started = SharingStarted.Lazily)

    override val sessionsFlow: StateFlow<List<ChatSessionDto>> = flow {
        durable {
            ChatRepositoryRpcApi
                .getInstance()
                .getSessionsFlow(project.projectId())
                .collect { emit(it) }
        }
    }.stateIn(coroutineScope, initialValue = emptyList(), started = SharingStarted.Lazily)

    override val planStatusFlow: StateFlow<ChatPlanStatusDto> = flow {
        durable {
            QuantaBackendApi
                .getInstance()
                .getPlanStatusFlow(project.projectId())
                .collect { emit(it) }
        }
    }.stateIn(coroutineScope, initialValue = ChatPlanStatusDto(), started = SharingStarted.Lazily)

    override val agentsFlow: StateFlow<List<AgentInfoDto>> = flow {
        durable {
            QuantaBackendApi
                .getInstance()
                .getAgentsFlow(project.projectId())
                .collect { emit(it) }
        }
    }.stateIn(coroutineScope, initialValue = emptyList(), started = SharingStarted.Lazily)

    override val delegatedTasksFlow: StateFlow<List<DelegatedTaskDto>> = flow {
        durable {
            QuantaBackendApi
                .getInstance()
                .getDelegatedTasksFlow(project.projectId())
                .collect { emit(it) }
        }
    }.stateIn(coroutineScope, initialValue = emptyList(), started = SharingStarted.Lazily)

    override val channelEventsFlow: StateFlow<List<AgentChannelEventDto>> = flow {
        durable {
            QuantaBackendApi
                .getInstance()
                .getChannelEventsFlow(project.projectId())
                .collect { emit(it) }
        }
    }.stateIn(coroutineScope, initialValue = emptyList(), started = SharingStarted.Lazily)

    override suspend fun sendMessage(messageContent: String) {
        ChatRepositoryRpcApi
            .getInstance()
            .sendMessage(project.projectId(), messageContent)
    }

    override suspend fun createNewSession() {
        ChatRepositoryRpcApi.getInstance().createNewSession(project.projectId())
    }

    override suspend fun activateSession(sessionId: String) {
        ChatRepositoryRpcApi.getInstance().activateSession(project.projectId(), sessionId)
    }

    override suspend fun deleteSession(sessionId: String) {
        ChatRepositoryRpcApi.getInstance().deleteSession(project.projectId(), sessionId)
    }

    override suspend fun setAgenticMode(enabled: Boolean) {
        val settings = FrontendQuantaSettingsState.instance.state
        settings.agenticEnabled = enabled
        FrontendSettingsRpcService.getInstance(project).updateSettings(settings.toDto())
    }

    override suspend fun createDefaultAgentTeam() {
        QuantaBackendApi.getInstance().createDefaultAgentTeam(project.projectId())
    }

    override suspend fun stopAllAgents(): Int =
        ChatRepositoryRpcApi.getInstance().stopAllAgents(project.projectId())
}
