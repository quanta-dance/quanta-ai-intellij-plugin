// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.settings

import com.github.quanta_dance.quanta.plugins.intellij.frontend.rpc.FrontendSettingsRpcService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FrontendSettingsSyncStateServiceTest {
    private lateinit var frontendState: FrontendQuantaSettingsState
    private lateinit var project: Project
    private lateinit var rpc: FrontendSettingsRpcService

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        mockkObject(FrontendSettingsRpcService.Companion)

        val app = mockk<Application>()
        frontendState = FrontendQuantaSettingsState()
        project = mockk(relaxed = true)
        rpc = mockk(relaxed = true)

        every { ApplicationManager.getApplication() } returns app
        every { app.getService(FrontendQuantaSettingsState::class.java) } returns frontendState
        every { FrontendSettingsRpcService.getInstance(project) } returns rpc
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `syncOnStartup marks state ready after successful sync`() =
        runBlocking {
            val service = FrontendSettingsSyncStateService(project)
            val backendDto =
                QuantaSettingsDto(
                    openAiUrl = "https://sync.test/v1/",
                    openAiToken = "token-123",
                    model = "gpt-5-mini",
                    aiChatModel = "gpt-5",
                    availableChatModels = listOf("gpt-5", "gpt-5-mini"),
                    voiceEnabled = false,
                    voiceByLocalTTS = true,
                    maxTokens = 4096,
                    dynamicModelEnabled = true,
                    agenticEnabled = false,
                    extraInstructions = "sync ok",
                    debugEnabled = true,
                    maxAutomaticTurns = 12,
                    followEnabled = true,
                    terminalToolEnabled = true,
                    terminalAllowedCommandsCsv = "git status",
                    actionConfigsJson = "[]",
                )

            coEvery { rpc.updateSettings(any()) } returns Unit
            coEvery { rpc.getSettings() } returns backendDto

            service.syncOnStartup()

            assertEquals(FrontendSettingsSyncStateService.Status.READY, service.stateFlow.value.status)
            assertEquals(backendDto.openAiUrl, frontendState.state.openAiUrl)
            assertEquals(backendDto.aiChatModel, frontendState.state.aiChatModel)
        }

    @Test
    fun `retryNow marks state failed after sync error`() =
        runBlocking {
            val service = FrontendSettingsSyncStateService(project)

            coEvery { rpc.updateSettings(any()) } throws IllegalStateException("rpc unavailable")

            service.retryNow()

            assertEquals(FrontendSettingsSyncStateService.Status.FAILED, service.stateFlow.value.status)
            assertEquals("rpc unavailable", service.stateFlow.value.lastErrorMessage)
        }
}
