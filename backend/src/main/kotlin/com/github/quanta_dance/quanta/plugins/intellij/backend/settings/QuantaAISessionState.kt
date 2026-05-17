// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.settings

import com.github.quanta_dance.quanta.plugins.intellij.backend.services.SessionPlan
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import kotlinx.serialization.Serializable

/**
 * Application-level persisted session store for Quanta chat and agent state.
 *
 * Despite living under `settings/`, this service no longer represents editable plugin settings.
 * Its responsibility is durable session persistence only:
 * - agent profiles and routing metadata
 * - chat conversation history
 * - per-agent inbox messages
 * - conversation summaries and last response pointers
 *
 * The persisted state key intentionally remains `QuantaAISettingsState` for compatibility with
 * existing `quanta-ai.xml` data. Keep migration-sensitive naming changes separate from routine
 * session-state cleanup.
 *
 * TODO: if agent registry responsibilities keep diverging from conversation persistence, split them
 * into dedicated persisted stores rather than growing this catch-all snapshot further.
 */
@Service(Service.Level.APP)
@State(
    name = QuantaAISessionState.PERSISTED_STATE_NAME,
    storages = [Storage(QuantaAISessionState.STORAGE_FILE)],
)
@Serializable
class QuantaAISessionState : PersistentStateComponent<QuantaAISessionState.State> {
    /**
     * Persisted agent identity/configuration for a session.
     *
     * This belongs here only while agent roster persistence remains coupled to session history.
     */
    @Serializable
    data class AgentProfile(
        var id: String = "",
        var role: String = "",
        var model: String? = null,
        var instructions: String? = null,
        var previousId: String? = null,
    )

    /**
     * A single persisted chat message associated with a conversation.
     */
    @Serializable
    data class PersistedMessage(
        var timestamp: Long = 0L,
        var role: String = "",
        var text: String = "",
        var responseId: String? = null,
    )

    /**
     * A message stored in an agent's inbox for later delivery/processing.
     */
    @Serializable
    data class AgentInboxMessage(
        var timestamp: Long = 0L,
        var from: String? = null,
        var text: String = "",
        var kind: String? = null,
    )

    /**
     * Complete persisted session snapshot.
     *
     * Keep this limited to durable conversation/agent-session data. User-editable runtime settings
     * belong in frontend persistence plus backend runtime sync, not here.
     */
    @Serializable
    data class State(
        var agents: MutableList<AgentProfile> = mutableListOf(),
        var conversations: MutableMap<String, MutableList<PersistedMessage>> = mutableMapOf(),
        var conversationSummaries: MutableMap<String, String> = mutableMapOf(),
        var agentInboxes: MutableMap<String, MutableList<AgentInboxMessage>> = mutableMapOf(),
        var sessionPlans: MutableMap<String, SessionPlan> = mutableMapOf(),
        var mainLastResponseId: String? = null,
    )

    companion object {
        const val PERSISTED_STATE_NAME: String = "QuantaAISettingsState"
        const val STORAGE_FILE: String = "quanta-ai.xml"

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
