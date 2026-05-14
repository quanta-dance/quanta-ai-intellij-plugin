// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import kotlinx.serialization.Serializable

/**
 * Application-level persisted AI session state.
 *
 * This stores runtime/session data that should survive IDE restarts:
 * - agent profiles
 * - chat conversation history
 * - per-agent inbox messages
 * - summaries and last response pointers
 *
 * TODO: consider splitting agent registry data from conversation history
 * if the session state grows further.
 * TODO: rename the persisted state key from QuantaAISettingsState to
 * QuantaAISessionState after existing data migration is handled.
 */
@Service(Service.Level.APP)
@State(
    name = "QuantaAISettingsState",
    storages = [Storage("quanta-ai.xml")],
)
@Serializable
class QuantaAISessionState : PersistentStateComponent<QuantaAISessionState.State> {
    /**
     * Persisted agent identity/configuration for a session.
     *
     * TODO: move this to a dedicated agent profile state if it becomes shared
     * between session persistence and backend configuration.
     */
    @Serializable
    data class AgentProfile(
        val id: String,
        val role: String,
        val model: String? = null,
        val instructions: String? = null,
        var previousId: String? = null,
    )

    /**
     * A single persisted chat message associated with a conversation.
     */
    @Serializable
    data class PersistedMessage(
        val timestamp: Long,
        val role: String,
        val text: String,
        val responseId: String? = null,
    )

    /**
     * A message stored in an agent's inbox for later delivery/processing.
     */
    @Serializable
    data class AgentInboxMessage(
        val timestamp: Long,
        val from: String?,
        val text: String,
        val kind: String?,
    )

    /**
     * The complete persisted session snapshot.
     *
     * TODO: split agent registry data from conversation state if the
     * responsibilities continue to diverge.
     */
    @Serializable
    data class State(
        // TODO: if agent profiles are shared with configuration later, extract a dedicated persistence type.
        var agents: MutableList<AgentProfile> = mutableListOf(),
        var conversations: MutableMap<String, MutableList<PersistedMessage>> = mutableMapOf(),
        var conversationSummaries: MutableMap<String, String> = mutableMapOf(),
        var agentInboxes: MutableMap<String, MutableList<AgentInboxMessage>> = mutableMapOf(),
        var mainLastResponseId: String? = null,
    )

    companion object {
        val instance: QuantaAISessionState
            get() = ApplicationManager.getApplication().service<QuantaAISessionState>()
    }

    @Volatile
    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }
}
