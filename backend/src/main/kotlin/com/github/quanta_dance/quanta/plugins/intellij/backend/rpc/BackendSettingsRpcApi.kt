// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaSettingsApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto
import com.openai.models.ChatModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backend implementation of the shared settings RPC.
 *
 * This class bridges frontend-owned configuration changes into
 * [BackendRuntimeSettingsService], which is the source of truth for runtime services running on the
 * backend process.
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
            val runtimeSettings = BackendRuntimeSettingsService.instance.settings
            QuantaSettingsDto(
                openAiUrl = runtimeSettings.openAiUrl,
                openAiToken = runtimeSettings.openAiToken,
                model = runtimeSettings.model,
                aiChatModel = runtimeSettings.aiChatModel,
                availableChatModels = AVAILABLE_CHAT_MODELS,
                voiceEnabled = runtimeSettings.voiceEnabled,
                voiceByLocalTTS = runtimeSettings.voiceByLocalTTS,
                maxTokens = runtimeSettings.maxTokens,
                dynamicModelEnabled = runtimeSettings.dynamicModelEnabled,
                agenticEnabled = runtimeSettings.agenticEnabled,
                terminalToolEnabled = runtimeSettings.terminalToolEnabled,
                terminalAllowedCommandsCsv = runtimeSettings.terminalAllowedCommandsCsv,
                extraInstructions = runtimeSettings.extraInstructions,
                debugEnabled = runtimeSettings.debugEnabled,
                maxAutomaticTurns = runtimeSettings.maxAutomaticTurns,
                followEnabled = true,
                actionConfigsJson = "",
            )
        }

    override suspend fun updateSettings(settings: QuantaSettingsDto) {
        withContext(Dispatchers.IO) {
            BackendRuntimeSettingsService.instance.updateFrom(settings)
        }
    }
}
