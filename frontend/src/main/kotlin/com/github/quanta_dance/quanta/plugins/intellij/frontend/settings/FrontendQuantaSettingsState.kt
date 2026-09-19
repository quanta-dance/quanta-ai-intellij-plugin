// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.settings

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpManualAgentDto
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(
    name = "QuantaDanceFrontendSettingsState",
    storages = [Storage("quantadance.frontend.xml")],
)
class FrontendQuantaSettingsState : PersistentStateComponent<FrontendQuantaSettingsState.State> {
    data class State(
        var openAiUrl: String = DEFAULT_OPENAI_URL,
        var openAiToken: String = "",
        var model: String = DEFAULT_MODEL,
        var aiChatModel: String = DEFAULT_MODEL,
        var availableChatModels: List<String> = emptyList(),
        var availableTtsVoices: List<String> = emptyList(),
        var voiceEnabled: Boolean = true,
        var voiceByLocalTTS: Boolean = false,
        var preferredOpenAiTtsVoice: String = "ash",
        var maxTokens: Long? = 2048,
        var dynamicModelEnabled: Boolean? = false,
        var agenticEnabled: Boolean? = true,
        var extraInstructions: String? = "",
        var debugEnabled: Boolean = false,
        var maxAutomaticTurns: Int = 10,
        var followEnabled: Boolean = true,
        var terminalToolEnabled: Boolean? = false,
        var terminalAllowedCommandsCsv: String = "git status,git diff,git add,git commit",
        var maxMessageWidth: Int = DEFAULT_MAX_MESSAGE_WIDTH,
        var actionConfigsJson: String = FrontendActionCatalog.encode(FrontendActionCatalog.defaultActions),
        var manualAcpAgents: List<AcpManualAgentDto> = emptyList(),
    )

    companion object {
        const val DEFAULT_OPENAI_URL = "https://api.openai.com/v1/"
        const val DEFAULT_MODEL = "gpt-5-nano"
        const val MIN_MESSAGE_WIDTH = 420
        const val MAX_MESSAGE_WIDTH = 2_000
        const val DEFAULT_MAX_MESSAGE_WIDTH = MIN_MESSAGE_WIDTH
        val MESSAGE_WIDTH_RANGE = MIN_MESSAGE_WIDTH..MAX_MESSAGE_WIDTH

        val instance: FrontendQuantaSettingsState
            get() = ApplicationManager.getApplication().service<FrontendQuantaSettingsState>()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        state.maxMessageWidth = state.maxMessageWidth.coerceIn(MESSAGE_WIDTH_RANGE)
        this.state = state
    }
}
