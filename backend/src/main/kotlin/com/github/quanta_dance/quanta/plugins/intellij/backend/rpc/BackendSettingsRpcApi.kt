// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaSettingsApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto
import com.openai.models.ChatModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backend implementation of the shared settings RPC.
 *
 * This class bridges frontend-owned configuration changes into [BackendQuantaSettingsState], which
 * is the source of truth for runtime services running on the backend process.
 */
class BackendSettingsRpcApi : QuantaSettingsApi {
    companion object {
        val AVAILABLE_CHAT_MODELS =
            listOf(
                //ChatModel.GPT_5_5.toString(),
                //ChatModel.GPT_5_5_MINI.toString(),
                //ChatModel.GPT_5_5_NANO.toString(),
                ChatModel.GPT_5_4.toString(),
                ChatModel.GPT_5_4_MINI.toString(),
                ChatModel.GPT_5_4_NANO.toString(),
                ChatModel.GPT_5_2.toString(),
                ChatModel.GPT_5_1_CODEX.toString(),
                ChatModel.GPT_5_1.toString(),
                ChatModel.GPT_5.toString(),
                ChatModel.GPT_5_MINI.toString(),
                ChatModel.GPT_5_NANO.toString(),
            )
    }

    override suspend fun getSettings(): QuantaSettingsDto =
        withContext(Dispatchers.IO) {
            val settings = BackendQuantaSettingsState.instance.settings
            QuantaSettingsDto(
                openAiUrl = settings.openAiUrl,
                openAiToken = settings.openAiToken,
                model = settings.model,
                aiChatModel = settings.aiChatModel,
                availableChatModels = AVAILABLE_CHAT_MODELS,
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
