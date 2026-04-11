package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaSettingsApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackendSettingsRpcApi : QuantaSettingsApi {
    override suspend fun getSettings(): QuantaSettingsDto =
        withContext(Dispatchers.IO) {
            val settings = BackendQuantaSettingsState.instance.settings
            QuantaSettingsDto(
                openAiUrl = settings.openAiUrl,
                openAiToken = settings.openAiToken,
                model = settings.model,
                aiChatModel = settings.aiChatModel,
                voiceEnabled = settings.voiceEnabled,
                voiceByLocalTTS = settings.voiceByLocalTTS,
                maxTokens = settings.maxTokens,
                dynamicModelEnabled = settings.dynamicModelEnabled,
                agenticEnabled = settings.agenticEnabled,
                terminalToolEnabled = settings.terminalToolEnabled,
                terminalAllowedCommandsCsv = settings.terminalAllowedCommandsCsv,
                extraInstructions = settings.extraInstructions,
                debugEnabled = settings.debugEnabled,
                maxAutomaticTurns = 10,
                followEnabled = true,
                actionConfigsJson = "",
            )
        }

    override suspend fun updateSettings(settings: QuantaSettingsDto) {
        withContext(Dispatchers.IO) {
            val backendSettings = BackendQuantaSettingsState.instance.settings
            backendSettings.openAiUrl = settings.openAiUrl
            backendSettings.openAiToken = settings.openAiToken
            backendSettings.model = settings.model
            backendSettings.aiChatModel = settings.aiChatModel
            backendSettings.voiceEnabled = settings.voiceEnabled
            backendSettings.voiceByLocalTTS = settings.voiceByLocalTTS
            backendSettings.maxTokens = settings.maxTokens
            backendSettings.dynamicModelEnabled = settings.dynamicModelEnabled
            backendSettings.agenticEnabled = settings.agenticEnabled
            backendSettings.terminalToolEnabled = settings.terminalToolEnabled
            backendSettings.terminalAllowedCommandsCsv = settings.terminalAllowedCommandsCsv
            backendSettings.extraInstructions = settings.extraInstructions
            backendSettings.debugEnabled = settings.debugEnabled
        }
    }
}