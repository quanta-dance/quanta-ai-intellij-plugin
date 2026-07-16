// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaSettingsApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto
import com.intellij.openapi.diagnostic.Logger
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
                // ChatModel.GPT_5_5.toString(),
                // ChatModel.GPT_5_5_MINI.toString(),
                // ChatModel.GPT_5_5_NANO.toString(),
                "gpt-5.6",
                "gpt-5.6-mini",
                "gpt-5.6-nano",
                "gpt-5.5",
                "gpt-5.5-mini",
                "gpt-5.5-nano",
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

    override suspend fun updateSettings(settings: QuantaSettingsDto) {
        BackendRuntimeSettingsService.instance.updateFrom(settings)
        /*   withContext(Dispatchers.IO) {
            val runtimeSettings = BackendRuntimeSettingsService.instance
            val previousJson = runtimeSettings.settings.mcpServersJson
            runtimeSettings.updateFrom(settings)

            val previousServers =
                McpServersConfigLoader
                    .loadJsonWithDiagnostics(previousJson, sourceName = "previous synced MCP configuration")
                    .file
                    ?.mcpServers
                    ?.keys
                    ?.sorted()
                    ?: emptyList()
            val currentServers =
                McpServersConfigLoader
                    .loadJsonWithDiagnostics(settings.mcpServersJson, sourceName = "current synced MCP configuration")
                    .file
                    ?.mcpServers
                    ?.keys
                    ?.sorted()
                    ?: emptyList()
            val addedServers = currentServers - previousServers.toSet()
            val removedServers = previousServers - currentServers.toSet()

            log.info(
                "Backend settings sync received MCP runtime config: chars=${settings.mcpServersJson.length}, " +
                        "servers=${currentServers.joinToString().ifBlank { "<none>" }}, " +
                        "added=${addedServers.joinToString().ifBlank { "<none>" }}, " +
                        "removed=${removedServers.joinToString().ifBlank { "<none>" }}",
            )
            addedServers.forEach { name ->
                log.info("Backend settings sync: MCP server added: $name")
            }
            removedServers.forEach { name ->
                log.info("Backend settings sync: MCP server removed: $name")
            }

            ProjectManager.getInstance().openProjects.forEach { project ->
                runCatching {
                    log.info("Backend settings sync: refreshing MCP runtime for project=${project.name}")
                    project.service<McpClientService>().refresh()
                }.onFailure { error ->
                    log.warn("Backend settings sync: failed to refresh MCP runtime for project=${project.name}", error)
                }
            }
        }*/
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
