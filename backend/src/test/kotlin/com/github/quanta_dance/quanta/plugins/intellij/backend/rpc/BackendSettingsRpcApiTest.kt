// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BackendSettingsRpcApiTest {
    private lateinit var runtimeSettingsService: BackendRuntimeSettingsService

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        val app = mockk<Application>()
        runtimeSettingsService = BackendRuntimeSettingsService()

        every { ApplicationManager.getApplication() } returns app
        every { app.getService(BackendRuntimeSettingsService::class.java) } returns runtimeSettingsService
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `updateSettings writes effective settings into backend runtime service`() =
        runBlocking {
            val api = BackendSettingsRpcApi()
            val dto =
                QuantaSettingsDto(
                    openAiUrl = "https://sync.test/v1/",
                    openAiToken = "token-123",
                    model = "gpt-5-mini",
                    aiChatModel = "gpt-5",
                    availableChatModels = emptyList(),
                    voiceEnabled = false,
                    voiceByLocalTTS = true,
                    maxTokens = 8192,
                    dynamicModelEnabled = true,
                    agenticEnabled = false,
                    extraInstructions = "Use tests first",
                    debugEnabled = true,
                    maxAutomaticTurns = 17,
                    followEnabled = false,
                    terminalToolEnabled = true,
                    terminalAllowedCommandsCsv = "git status,git diff",
                    actionConfigsJson = "[]",
                )

            api.updateSettings(dto)

            with(runtimeSettingsService.settings) {
                assertEquals(dto.openAiUrl, openAiUrl)
                assertEquals(dto.openAiToken, openAiToken)
                assertEquals(dto.aiChatModel, aiChatModel)
                assertEquals(dto.agenticEnabled, agenticEnabled)
                assertEquals(dto.maxAutomaticTurns, maxAutomaticTurns)
                assertEquals(dto.terminalAllowedCommandsCsv, terminalAllowedCommandsCsv)
            }
        }

    @Test
    fun `getSettings returns snapshot from backend runtime service`() =
        runBlocking {
            val api = BackendSettingsRpcApi()
            runtimeSettingsService.updateFrom(
                QuantaSettingsDto(
                    openAiUrl = "https://readback.test/v1/",
                    openAiToken = "readback-token",
                    model = "gpt-5-nano",
                    aiChatModel = "gpt-5-mini",
                    availableChatModels = emptyList(),
                    voiceEnabled = true,
                    voiceByLocalTTS = false,
                    maxTokens = 1024,
                    dynamicModelEnabled = false,
                    agenticEnabled = true,
                    extraInstructions = "Stay focused",
                    debugEnabled = false,
                    maxAutomaticTurns = 9,
                    followEnabled = true,
                    terminalToolEnabled = false,
                    terminalAllowedCommandsCsv = "git status",
                    actionConfigsJson = "{}",
                ),
            )

            val dto = api.getSettings()

            assertEquals("https://readback.test/v1/", dto.openAiUrl)
            assertEquals("readback-token", dto.openAiToken)
            assertEquals("gpt-5-mini", dto.aiChatModel)
            assertEquals(true, dto.agenticEnabled)
            assertEquals(9, dto.maxAutomaticTurns)
            assertEquals("git status", dto.terminalAllowedCommandsCsv)
            assertEquals(BackendSettingsRpcApi.AVAILABLE_CHAT_MODELS, dto.availableChatModels)
        }
}
