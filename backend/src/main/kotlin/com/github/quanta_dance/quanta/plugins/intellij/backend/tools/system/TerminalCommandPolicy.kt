// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.system

internal fun validateAllowedTerminalCommand(
    command: String,
    enabled: Boolean,
    allowedCommandsCsv: String,
): String? {
    if (!enabled) return "Terminal command tool is disabled."

    val allowedPrefixes =
        allowedCommandsCsv
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(Regex("\\s+")) }

    if (allowedPrefixes.isEmpty()) return "No terminal command prefixes are configured."
    if (command.isBlank()) return "Command is not specified."

    val shellOperator = Regex("[;&|<>`$%\\n\\r\\u0000\\u001b^]")
    if (shellOperator.containsMatchIn(command)) {
        return "Command contains shell syntax that is not allowed."
    }

    val commandTokens = command.trim().split(Regex("\\s+"))
    val allowed =
        allowedPrefixes.any { prefix ->
            commandTokens.size >= prefix.size &&
                prefix.indices.all { index -> commandTokens[index] == prefix[index] }
        }
    if (allowed) return null

    val allowedText = allowedPrefixes.joinToString(", ") { it.joinToString(" ") }
    return "Command is not allowed: '$command'. Allowed prefixes: $allowedText"
}
