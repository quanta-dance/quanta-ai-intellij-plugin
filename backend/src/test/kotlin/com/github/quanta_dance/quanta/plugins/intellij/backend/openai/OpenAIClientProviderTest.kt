// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class OpenAIClientProviderTest {
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
    fun `get fails fast before frontend sync completes`() {
        val project = mockk<Project>(relaxed = true)

        assertFailsWith<IllegalStateException> {
            OpenAIClientProvider.get(project)
        }
    }

    @Test
    fun `get builds client after frontend sync`() {
        val project = mockk<Project>(relaxed = true)
        runtimeSettingsService.updateFrom(
            QuantaSettingsDto(
                openAiUrl = "https://example.test/v1/",
                openAiToken = "token-123",
                model = "gpt-5-mini",
                aiChatModel = "gpt-5-mini",
                availableChatModels = emptyList(),
                voiceEnabled = true,
                voiceByLocalTTS = false,
                maxTokens = 1024,
                dynamicModelEnabled = false,
                agenticEnabled = true,
                extraInstructions = "",
                debugEnabled = false,
                maxAutomaticTurns = 10,
                followEnabled = true,
                terminalToolEnabled = false,
                terminalAllowedCommandsCsv = "git status",
                actionConfigsJson = "[]",
            ),
        )

        assertNotNull(OpenAIClientProvider.get(project))
    }
}
