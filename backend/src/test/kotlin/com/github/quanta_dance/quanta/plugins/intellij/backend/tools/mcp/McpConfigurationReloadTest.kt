// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpConfigurationReloadTest {
    @Test
    fun `configuration file replacement identifies added removed and changed servers`() {
        val initial =
            loadConfig(
                """
                {
                  "mcpServers": {
                    "retained": { "command": "npx", "args": ["-y", "retained-mcp@1"] },
                    "removed": { "url": "https://removed.example.test/mcp" },
                    "unchanged": { "command": "echo", "args": ["stable"] }
                  }
                }
                """.trimIndent(),
            )
        val replacement =
            loadConfig(
                """
                {
                  "mcpServers": {
                    "retained": { "command": "npx", "args": ["-y", "retained-mcp@2"] },
                    "unchanged": { "command": "echo", "args": ["stable"] },
                    "added": { "url": "https://added.example.test/mcp", "headers": { "X-Token": "new-token" } }
                  }
                }
                """.trimIndent(),
            )

        val changes = calculateMcpServerConfigChanges(initial, replacement)

        assertEquals(setOf("added"), changes.added)
        assertEquals(setOf("removed"), changes.removed)
        assertEquals(setOf("retained"), changes.changed)
    }

    @Test
    fun `invalid replacement is rejected before it can replace the active configuration`() {
        val active =
            loadConfig(
                """
                {
                  "mcpServers": {
                    "available": { "command": "echo", "args": ["available"] }
                  }
                }
                """.trimIndent(),
            )

        val replacement =
            McpServersConfigLoader.loadJsonWithDiagnostics(
                """{ "mcpServers": { "broken": """",
                sourceName = "changed-mcp-servers.json",
            )

        val unchanged = calculateMcpServerConfigChanges(active, active)

        assertNull(replacement.file)
        assertNotNull(replacement.parseError)
        assertEquals(listOf("available"), active.mcpServers.keys.toList())
        assertTrue(unchanged.added.isEmpty())
        assertTrue(unchanged.removed.isEmpty())
        assertTrue(unchanged.changed.isEmpty())
    }

    private fun loadConfig(json: String): McpServersFile {
        val result = McpServersConfigLoader.loadJsonWithDiagnostics(json, sourceName = "test-mcp-servers.json")
        assertNull(result.parseError)
        return assertNotNull(result.file)
    }
}
