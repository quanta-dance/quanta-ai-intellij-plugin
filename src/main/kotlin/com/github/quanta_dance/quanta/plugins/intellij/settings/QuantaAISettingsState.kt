// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.openai.models.ChatModel

@State(
    name = "QuantaDanceSettingsState",
    storages = [Storage("quantadance.xml")],
)
class QuantaAISettingsState : PersistentStateComponent<QuantaAISettingsState.QuantaAIState> {
    companion object {
        val instance: QuantaAISettingsState
            get() = ApplicationManager.getApplication().service<QuantaAISettingsState>()

        const val DEFAULT_HOST = "https://api.openai.com/v1/"
    }

    data class PersistedAgent(
        var id: String = "",
        var role: String = "",
        var model: String? = null,
        var instructions: String? = null,
        var previousId: String? = null,
    )

    // Persisted inbox message structure (agent-to-agent / manager-to-agent notifications)
    data class AgentInboxMessage(
        var timestamp: Long = System.currentTimeMillis(),
        var from: String? = null,
        var text: String = "",
        // e.g. "notification" | "roster_update"
        var kind: String? = null,
    )

    // default configuration
    data class QuantaAIState(
        var host: String = DEFAULT_HOST,
        var token: String = "",
        var voiceEnabled: Boolean = true,
        var voiceByLocalTTS: Boolean = false,
        var customPrompt: String = "Review this code and suggest changes",
        var maxTokens: Long? = 2048,
        var aiChatModel: String = ChatModel.GPT_5_NANO.toString(),
        // Dynamic model switching
        var dynamicModelEnabled: Boolean? = false,
        // Agentic mode toggle (manager can spawn agents and use agent tools)
        var agenticEnabled: Boolean? = true,
        // Optional: user-customizable extra system instructions appended to defaults
        var extraInstructions: String? = "",
        // Persistence for main and agents conversations
        var mainLastResponseId: String? = null,
        var agents: MutableList<PersistedAgent> = mutableListOf(),
        // Stored conversations keyed by conversation id (e.g., "main@<branch>")
        var conversations: MutableMap<String, MutableList<PersistedMessage>> = mutableMapOf(),
        // Optional rolling summaries per conversation key (used to keep context small)
        var conversationSummaries: MutableMap<String, String> = mutableMapOf(),
        // Per-agent inboxes (asynchronous notifications). Key: agentId
        var agentInboxes: MutableMap<String, MutableList<AgentInboxMessage>> = mutableMapOf(),
        // Developer-only: show additional debug details in the tool window UI (default off)
        var debugEnabled: Boolean = false,
        // Max automatic turns (CONTINUE loops) allowed per user turn. Clamped to [1..100].
        var maxAutomaticTurns: Int = 10,
        // Security: Terminal tool availability (default disabled)
        var terminalToolEnabled: Boolean? = false,
        // Security: allowed terminal command prefixes (strict token-prefix match), comma-separated.
        // Each entry may contain spaces. Examples: "git status", "git diff", "git add", "git commit", "./gradlew"
        var terminalAllowedCommandsCsv: String = "git status,git diff,git add,git commit",
    )

    // Persisted message structure
    data class PersistedMessage(
        var timestamp: Long = System.currentTimeMillis(),
        // "user" | "assistant" | "system"
        var role: String = "",
        var text: String = "",
        var responseId: String? = null,
    )

    private var state = QuantaAIState()

    override fun getState(): QuantaAIState = state

    override fun loadState(state: QuantaAIState) {
        this.state = state
    }
}
