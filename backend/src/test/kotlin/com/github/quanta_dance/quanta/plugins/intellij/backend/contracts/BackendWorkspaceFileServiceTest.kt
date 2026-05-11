// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.contracts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileReadRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileWriteRequest
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.*

class BackendWorkspaceFileServiceTest {
    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        val app = mockk<Application>()
        every { ApplicationManager.getApplication() } returns app
        every { app.runReadAction(any<Computable<Any>>()) } answers { firstArg<Computable<Any>>().compute() }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `read rejects blank paths with friendly backend error`() = kotlinx.coroutines.runBlocking {
        val result = BackendWorkspaceFileService().read(WorkspaceFileReadRequest("   "))

        assertFalse(result.success)
        assertEquals("Path is not specified.", result.error)
        assertEquals("backend", result.source)
    }

    @Test
    fun `write rejects blank paths with friendly backend error`() = kotlinx.coroutines.runBlocking {
        val result = BackendWorkspaceFileService().write(WorkspaceFileWriteRequest("   ", "hello"))

        assertFalse(result.success)
        assertEquals("Path is not specified.", result.error)
        assertEquals("backend", result.source)
    }
}
