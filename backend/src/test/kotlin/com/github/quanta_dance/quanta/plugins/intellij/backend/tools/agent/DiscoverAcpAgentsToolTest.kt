// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoverAcpAgentsToolTest {
    private val tool = DiscoverAcpAgentsTool()

    @Test
    fun `returns discovered ACP agents and a summary`() {
        val agent =
            AcpAgentDto(
                id = "opencode-id",
                name = "OpenCode",
                command = listOf("opencode", "acp"),
                executablePath = "/usr/local/bin/opencode",
                version = "1.0.0",
                protocolVersion = 1,
            )

        val result = tool.discoveryResult(listOf(agent))

        assertEquals(
            listOf(
                mapOf(
                    "id" to "opencode-id",
                    "name" to "OpenCode",
                    "command" to listOf("opencode", "acp"),
                    "executablePath" to "/usr/local/bin/opencode",
                    "version" to "1.0.0",
                    "protocolVersion" to 1,
                    "allowedForCurrentChat" to false,
                ),
            ),
            result["agents"],
        )
        assertEquals(1, result["foundCount"])
        assertEquals(0, result["allowedCount"])
        assertEquals("Found 1 ACP-compatible agent(s); 0 enabled for this chat.", result["message"])
    }

    @Test
    fun `returns an actionable empty result`() {
        val result = tool.discoveryResult(emptyList())

        assertEquals(emptyList<Map<String, Any>>(), result["agents"])
        assertEquals(0, result["foundCount"])
        assertEquals(0, result["allowedCount"])
        assertEquals(
            "No ACP-compatible agents were found on the backend PATH or in manual ACP settings.",
            result["message"],
        )
    }
}
