// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolRouterTest {
    @Test
    fun `unwraps nested tool friendly exception from invokeAndWait wrappers`() {
        val project = mockk<Project>(relaxed = true)
        val functionCall = mockk<ResponseFunctionToolCall>()
        every { functionCall.name() } returns "CreateOrUpdateFile"
        every { functionCall.arguments() } returns "{}"

        val invoker =
            object : ToolInvoker {
                override fun invoke(
                    project: Project,
                    functionCall: ResponseFunctionToolCall,
                ): Any =
                    throw RuntimeException(
                        ToolFriendlyException(
                            "Write operation was cancelled while updating file before completion. Retry may succeed.",
                            code = "cancelled",
                            retriable = true,
                        ),
                    )
            }

        val result = ToolRouter(project, invoker, ObjectMapper()).route(functionCall) as Map<*, *>

        assertEquals("error", result["status"])
        assertEquals("cancelled", result["code"])
        assertEquals(true, result["retriable"])
        assertTrue(result["message"].toString().contains("Retry may succeed"))
    }

    @Test
    fun `normalizes raw message cancel text`() {
        val project = mockk<Project>(relaxed = true)
        val functionCall = mockk<ResponseFunctionToolCall>()
        every { functionCall.name() } returns "RunGoTestsTool"
        every { functionCall.arguments() } returns "{}"

        val invoker =
            object : ToolInvoker {
                override fun invoke(
                    project: Project,
                    functionCall: ResponseFunctionToolCall,
                ): Any = throw java.util.concurrent.CancellationException("Cancelled by Message.Cancel")
            }

        val result = ToolRouter(project, invoker, ObjectMapper()).route(functionCall) as Map<*, *>

        assertEquals("error", result["status"])
        assertEquals("cancelled", result["code"])
        assertEquals("Execution of RunGoTestsTool was cancelled before completion.", result["message"])
    }
}
