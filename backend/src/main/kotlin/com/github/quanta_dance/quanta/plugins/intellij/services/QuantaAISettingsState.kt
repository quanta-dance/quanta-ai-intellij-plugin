// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import kotlinx.serialization.Serializable

@Service(Service.Level.APP)
@State(
    name = "QuantaAISettingsState",
    storages = [Storage("quanta-ai.xml")],
)
@Serializable
class QuantaAISettingsState : PersistentStateComponent<QuantaAISettingsState.State> {
    @Serializable
    data class AgentProfile(
        val id: String,
        val role: String,
        val model: String? = null,
        val instructions: String? = null,
        var previousId: String? = null,
    )

    @Serializable
    data class PersistedMessage(
        val timestamp: Long,
        val role: String,
        val text: String,
        val responseId: String? = null,
    )

    @Serializable
    data class AgentInboxMessage(
        val timestamp: Long,
        val from: String?,
        val text: String,
        val kind: String?,
    )

    @Serializable
    data class State(
        var agenticEnabled: Boolean? = true,
        var maxAutomaticTurns: Int = 10,
        var agents: MutableList<AgentProfile> = mutableListOf(),
        var conversations: MutableMap<String, MutableList<PersistedMessage>> = mutableMapOf(),
        var conversationSummaries: MutableMap<String, String> = mutableMapOf(),
        var agentInboxes: MutableMap<String, MutableList<AgentInboxMessage>> = mutableMapOf(),
        var mainLastResponseId: String? = null,
    )

    companion object {
        val instance: QuantaAISettingsState
            get() = ApplicationManager.getApplication().service<QuantaAISettingsState>()
    }

    @Volatile
    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }
}
