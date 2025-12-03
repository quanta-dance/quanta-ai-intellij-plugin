// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class QuantaAIPluginConfigurable : Configurable {
    private val settingsComponent: QuantaAISettingsComponent by lazy(::QuantaAISettingsComponent)

    override fun getDisplayName(): String {
        return "QuantaDance Settings"
    }

    override fun createComponent(): JComponent = settingsComponent.panel

    override fun isModified(): Boolean {
        val settings = QuantaAISettingsState.instance.state
        return settingsComponent.run {
            this.hostValue != settings.host ||
                this.tokenValue != settings.token ||
                this.voiceEnabled != settings.voiceEnabled ||
                this.voiceByLocalTTS != settings.voiceByLocalTTS ||
                this.customPromptValue != settings.customPrompt ||
                this.maxTokensValue != settings.maxTokens ||
                this.aiChatModelValue != settings.aiChatModel ||
                this.extraInstructionsValue != (settings.extraInstructions ?: "") ||
                this.dynamicModelEnabled != (settings.dynamicModelEnabled ?: true) ||
                this.agenticEnabled != (settings.agenticEnabled ?: true)
        }
    }

    override fun apply() {
        val settings = QuantaAISettingsState.instance.state
        settingsComponent.run {
            settings.host = this.hostValue
            settings.token = this.tokenValue
            settings.voiceEnabled = this.voiceEnabled
            settings.voiceByLocalTTS = this.voiceByLocalTTS
            settings.customPrompt = this.customPromptValue
            settings.maxTokens = this.maxTokensValue
            settings.aiChatModel = this.aiChatModelValue
            settings.extraInstructions = this.extraInstructionsValue
            settings.dynamicModelEnabled = this.dynamicModelEnabled
            settings.agenticEnabled = this.agenticEnabled
        }
        val snapshot = settings.copy()
        ApplicationManager.getApplication()
            .messageBus
            .syncPublisher(QuantaAISettingsListener.TOPIC)
            .onSettingsChanged(snapshot)
    }

    override fun reset() {
        val settings = QuantaAISettingsState.instance.state
        settingsComponent.run {
            this.hostValue = settings.host
            this.tokenValue = settings.token
            this.voiceEnabled = settings.voiceEnabled
            this.voiceByLocalTTS = settings.voiceByLocalTTS
            this.customPromptValue = settings.customPrompt
            this.maxTokensValue = settings.maxTokens
            this.aiChatModelValue = settings.aiChatModel
            this.extraInstructionsValue = settings.extraInstructions.orEmpty()
            this.dynamicModelEnabled = (settings.dynamicModelEnabled ?: true)
            this.agenticEnabled = (settings.agenticEnabled ?: true)
        }
    }
}
