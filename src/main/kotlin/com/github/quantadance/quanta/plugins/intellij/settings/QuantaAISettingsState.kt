// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.settings

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
        // Optional: user-customizable extra system instructions appended to defaults
        var extraInstructions: String? = "",
    )

    private var state = QuantaAIState()

    override fun getState(): QuantaAIState {
        return state
    }

    override fun loadState(state: QuantaAIState) {
        this.state = state
    }
}
