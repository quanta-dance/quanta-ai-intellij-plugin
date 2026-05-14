// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

/**
 * Transport-neutral frontend settings snapshot exchanged between frontend and backend.
 *
 * The frontend remains the persistence owner for these values. In split-mode this DTO carries the
 * effective user settings to the backend so runtime services can execute without persisting a second
 * backend-owned copy.
 */
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
