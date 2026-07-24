// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ToolsRegistry
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.system.TerminalCommandTool
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolExecutionPresenterTest {
    @Test
    fun `terminal tool presentation keeps generic title and command detail`() {
        val functionCall =
            mockFunctionCall(
                name = "TerminalCommandTool",
                arguments = """{"action":"RUN","command":"git status --short"}""",
            )
        val project = mockk<Project>(relaxed = true)

        mockkObject(ToolsRegistry)
        try {
            every { ToolsRegistry.toolsFor(project) } returns terminalTools()
            val item =
                ToolExecutionPresenter(project, ObjectMapper()).buildToolExecutionItem(
                    functionCall = functionCall,
                    status = ToolExecutionStatus.SUCCEEDED,
                    displaySummary = "Terminal Command Tool: git status --short",
                    detailText = null,
                )

            assertEquals("TerminalCommandTool", item.toolName)
            assertEquals("Terminal Command Tool", item.displayText)
            assertEquals("Command: git status --short", item.detailText)
        } finally {
            unmockkObject(ToolsRegistry)
        }
    }

    @Test
    fun `terminal tool presentation keeps generic title when error summary is huge`() {
        val disallowedMessage =
            "Command is not allowed: 'printf '%s\\n' test'. Allowed prefixes: git, bash, sh, kubectl"
        val functionCall =
            mockFunctionCall(
                name = "TerminalCommandTool",
                arguments = """{"action":"RUN","command":"printf '%s\\n' test"}""",
            )
        val project = mockk<Project>(relaxed = true)

        mockkObject(ToolsRegistry)
        try {
            every { ToolsRegistry.toolsFor(project) } returns terminalTools()
            val item =
                ToolExecutionPresenter(project, ObjectMapper()).buildToolExecutionItem(
                    functionCall = functionCall,
                    status = ToolExecutionStatus.FAILED,
                    displaySummary = disallowedMessage,
                    errorText = disallowedMessage,
                    detailText = disallowedMessage,
                )

            assertEquals("Terminal Command Tool", item.displayText)
            assertEquals(disallowedMessage, item.errorText)
            assertEquals(disallowedMessage, item.detailText)
        } finally {
            unmockkObject(ToolsRegistry)
        }
    }

    @Test
    fun `terminal tool presentation uses job id detail for non run actions`() {
        val functionCall =
            mockFunctionCall(
                name = "TerminalCommandTool",
                arguments = """{"action":"WAIT","jobId":"job-123"}""",
            )
        val project = mockk<Project>(relaxed = true)

        mockkObject(ToolsRegistry)
        try {
            every { ToolsRegistry.toolsFor(project) } returns terminalTools()
            val item =
                ToolExecutionPresenter(project, ObjectMapper()).buildToolExecutionItem(
                    functionCall = functionCall,
                    status = ToolExecutionStatus.EXECUTING,
                )

            assertEquals("Terminal Command Tool", item.displayText)
            assertEquals("Job: job-123", item.detailText)
        } finally {
            unmockkObject(ToolsRegistry)
        }
    }

    @Test
    fun `terminal tool class presentation exposes stable title`() {
        val tool =
            TerminalCommandTool().apply {
                action = "RUN"
                command = "git diff --stat"
            }

        val presentation = tool.presentation(ToolExecutionStatus.EXECUTING)

        assertEquals("Terminal Command Tool", presentation.title)
        assertEquals("Command: git diff --stat", presentation.detail)
    }

    @Test
    fun `terminal tool summary strings can include command without exploding title`() {
        val functionCall =
            mockFunctionCall(
                name = "TerminalCommandTool",
                arguments = """{"action":"READ","jobId":"job-7"}""",
            )
        val project = mockk<Project>(relaxed = true)

        mockkObject(ToolsRegistry)
        try {
            every { ToolsRegistry.toolsFor(project) } returns terminalTools()
            val item =
                ToolExecutionPresenter(project, ObjectMapper()).buildToolExecutionItem(
                    functionCall = functionCall,
                    status = ToolExecutionStatus.SUCCEEDED,
                    displaySummary = "Terminal Command Tool: git log --oneline -n 20",
                    detailText = "Read combined tail output for terminal job job-7.",
                )

            assertEquals("Terminal Command Tool", item.displayText)
            assertTrue(item.detailText!!.contains("job-7"))
        } finally {
            unmockkObject(ToolsRegistry)
        }
    }

    private fun mockFunctionCall(
        name: String,
        arguments: String,
    ): ResponseFunctionToolCall =
        mockk<ResponseFunctionToolCall>().also { functionCall ->
            every { functionCall.name() } returns name
            every { functionCall.arguments() } returns arguments
            every { functionCall.callId() } returns "call-1"
        }

    @Suppress("UNCHECKED_CAST")
    private fun terminalTools(): List<Class<out ToolInterface<out Any>>> =
        listOf(TerminalCommandTool::class.java as Class<out ToolInterface<out Any>>)
}
