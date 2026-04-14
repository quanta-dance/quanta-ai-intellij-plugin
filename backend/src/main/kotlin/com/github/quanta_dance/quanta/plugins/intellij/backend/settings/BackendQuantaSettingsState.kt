// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.settings

import com.github.quanta_dance.quanta.plugins.intellij.services.QuantaAISettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "BackendQuantaSettingsState",
    storages = [Storage("quanta.backend.xml")],
)
class BackendQuantaSettingsState : PersistentStateComponent<BackendQuantaSettingsState.State> {
    data class State(
        var openAiUrl: String = "https://api.openai.com/v1/",
        var openAiToken: String = "",
        var model: String = "gpt-5-nano",
        var aiChatModel: String = "gpt-5-nano",
        var voiceEnabled: Boolean = true,
        var voiceByLocalTTS: Boolean = false,
        var maxTokens: Long? = 2048,
        var dynamicModelEnabled: Boolean? = false,
        var agenticEnabled: Boolean? = true,
        var maxAutomaticTurns: Int = 10,
        var terminalToolEnabled: Boolean? = false,
        var terminalAllowedCommandsCsv: String = "git status,git diff,git add,git commit",
        var extraInstructions: String? = "",
        var debugEnabled: Boolean = false,
        var agents: MutableList<QuantaAISettingsState.AgentProfile> = mutableListOf(),
    )

    companion object {
        val instance: BackendQuantaSettingsState
            get() = ApplicationManager.getApplication().getService(BackendQuantaSettingsState::class.java)
    }

    @Volatile
    private var state: State = State()

    val settings: State
        get() = state

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }
}
