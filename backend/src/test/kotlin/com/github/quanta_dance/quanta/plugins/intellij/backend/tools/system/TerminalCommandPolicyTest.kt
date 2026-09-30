// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TerminalCommandPolicyTest {
    @Test
    fun `allows configured command prefixes and ordinary arguments`() {
        assertNull(
            validateAllowedTerminalCommand(
                command = "git status --short",
                enabled = true,
                allowedCommandsCsv = "git status,git diff",
            ),
        )
    }

    @Test
    fun `denies commands when the tool is disabled or the allowlist is empty`() {
        assertEquals(
            "Terminal command tool is disabled.",
            validateAllowedTerminalCommand("git status", false, "git status"),
        )
        assertEquals(
            "No terminal command prefixes are configured.",
            validateAllowedTerminalCommand("git status", true, " , "),
        )
    }

    @Test
    fun `requires a full token prefix match`() {
        assertNotNull(validateAllowedTerminalCommand("git status", true, "git status --short"))
        assertNotNull(validateAllowedTerminalCommand("git-status", true, "git status"))
    }

    @Test
    fun `rejects command chaining substitution redirection and control characters`() {
        val maliciousCommands =
            listOf(
                "git status; rm -rf .",
                "git status && rm -rf .",
                "git status | sh",
                "git status > /tmp/result",
                "git status $(touch /tmp/pwned)",
                "git status `touch /tmp/pwned`",
                "git status %COMSPEC%",
                "git status\nrm -rf .",
            )

        maliciousCommands.forEach { command ->
            assertNotNull(
                validateAllowedTerminalCommand(command, true, "git status"),
                "Expected command to be rejected: $command",
            )
        }
    }
}
