// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp.McpClientService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaSettingsApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.openai.models.AllModels
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
    private val log = Logger.getInstance(BackendSettingsRpcApi::class.java)

    companion object {
        val AVAILABLE_CHAT_MODELS =
            listOf(
                AllModels.ResponsesOnlyModel.GPT_5_6_CYBER.toString(),
                ChatModel.GPT_5_6_SOL.toString(),
                ChatModel.GPT_5_6_TERRA.toString(),
                ChatModel.GPT_5_6_LUNA.toString(),
                ChatModel.GPT_5_4.toString(),
                ChatModel.GPT_5_4_MINI.toString(),
                ChatModel.GPT_5_4_NANO.toString(),
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
                availableTtsVoices = availableTtsVoices(),
                voiceEnabled = runtimeSettings.voiceEnabled,
                voiceByLocalTTS = runtimeSettings.voiceByLocalTTS,
                preferredOpenAiTtsVoice = runtimeSettings.preferredOpenAiTtsVoice,
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
                mcpServersJson = runtimeSettings.mcpServersJson,
            )
        }

    override suspend fun updateSettings(settings: QuantaSettingsDto) =
        withContext(Dispatchers.IO) {
            val previousJson = BackendRuntimeSettingsService.instance.settings.mcpServersJson
            BackendRuntimeSettingsService.instance.updateFrom(settings)

            if (settings.mcpServersJson != previousJson) {
                log.info(
                    "Backend settings sync: MCP config changed (chars=${settings.mcpServersJson.length}), refreshing MCP runtime",
                )
                ProjectManager.getInstance().openProjects.forEach { project ->
                    runCatching {
                        project.service<McpClientService>().refresh()
                    }.onFailure { error ->
                        log.warn("Backend settings sync: failed to refresh MCP for project=${project.name}", error)
                    }
                }
            } else {
                log.info("Backend settings sync: MCP config unchanged, skipping refresh")
            }
        }

    private fun availableTtsVoices(): List<String> {
        val reflected =
            runCatching {
                val enumClass = Class.forName("com.openai.models.audio.speech.SpeechCreateParams\$Voice\$UnionMember1")
                (enumClass.enumConstants ?: emptyArray<Any>())
                    .mapNotNull { (it as? Enum<*>)?.name?.lowercase() }
                    .distinct()
                    .sorted()
            }.getOrDefault(emptyList())
        return if (reflected.isNotEmpty()) {
            reflected
        } else {
            listOf(
                "alloy",
                "ash",
                "ballad",
                "coral",
                "echo",
                "fable",
                "nova",
                "onyx",
                "sage",
                "shimmer",
                "verse",
            )
        }
    }
}
