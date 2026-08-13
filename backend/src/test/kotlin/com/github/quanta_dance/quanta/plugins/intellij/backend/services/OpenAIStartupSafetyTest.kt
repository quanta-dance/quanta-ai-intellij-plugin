// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.QuantaAISessionState
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

class OpenAIStartupSafetyTest {
    private lateinit var runtimeSettingsService: BackendRuntimeSettingsService
    private lateinit var sessionState: QuantaAISessionState

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        val app = mockk<Application>()
        runtimeSettingsService = BackendRuntimeSettingsService()
        sessionState = QuantaAISessionState()

        every { ApplicationManager.getApplication() } returns app
        every { app.getService(BackendRuntimeSettingsService::class.java) } returns runtimeSettingsService
        every { app.getService(QuantaAISessionState::class.java) } returns sessionState
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `open ai service can be created before frontend sync completes`() {
        val project = mockk<Project>(relaxed = true)

        OpenAIService(project)
    }
}
