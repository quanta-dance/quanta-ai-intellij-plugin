package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

@Serializable
data class QuantaSettingsDto(
    val openAiUrl: String,
    val openAiToken: String,
    val model: String,
    val aiChatModel: String,
    val availableChatModels: List<String> = emptyList(),
    val voiceEnabled: Boolean,
    val voiceByLocalTTS: Boolean,
    val maxTokens: Long?,
    val dynamicModelEnabled: Boolean?,
    val agenticEnabled: Boolean?,
    val extraInstructions: String?,
    val debugEnabled: Boolean,
    val maxAutomaticTurns: Int,
    val followEnabled: Boolean,
    val terminalToolEnabled: Boolean?,
    val terminalAllowedCommandsCsv: String,
    val actionConfigsJson: String,
)