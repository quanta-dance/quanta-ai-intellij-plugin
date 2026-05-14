// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.settings

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto
import kotlin.test.Test
import kotlin.test.assertEquals

class BackendRuntimeSettingsServiceTest {
    @Test
    fun `updateFrom copies frontend owned settings into backend runtime snapshot`() {
        val service = BackendRuntimeSettingsService()
        val dto =
            QuantaSettingsDto(
                openAiUrl = "https://example.test/v1/",
                openAiToken = "secret-token",
                model = "gpt-5-mini",
                aiChatModel = "gpt-5",
                availableChatModels = listOf("gpt-5", "gpt-5-mini"),
                voiceEnabled = false,
                voiceByLocalTTS = true,
                maxTokens = 4096,
                dynamicModelEnabled = true,
                agenticEnabled = false,
                extraInstructions = "Be concise",
                debugEnabled = true,
                maxAutomaticTurns = 42,
                followEnabled = false,
                terminalToolEnabled = true,
                terminalAllowedCommandsCsv = "git status,./gradlew test",
                actionConfigsJson = "[]",
            )

        service.updateFrom(dto)

        with(service.settings) {
            assertEquals(dto.openAiUrl, openAiUrl)
            assertEquals(dto.openAiToken, openAiToken)
            assertEquals(dto.model, model)
            assertEquals(dto.aiChatModel, aiChatModel)
            assertEquals(dto.voiceEnabled, voiceEnabled)
            assertEquals(dto.voiceByLocalTTS, voiceByLocalTTS)
            assertEquals(dto.maxTokens, maxTokens)
            assertEquals(dto.dynamicModelEnabled, dynamicModelEnabled)
            assertEquals(dto.agenticEnabled, agenticEnabled)
            assertEquals(dto.extraInstructions, extraInstructions)
            assertEquals(dto.debugEnabled, debugEnabled)
            assertEquals(dto.maxAutomaticTurns, maxAutomaticTurns)
            assertEquals(dto.terminalToolEnabled, terminalToolEnabled)
            assertEquals(dto.terminalAllowedCommandsCsv, terminalAllowedCommandsCsv)
        }
    }
}
