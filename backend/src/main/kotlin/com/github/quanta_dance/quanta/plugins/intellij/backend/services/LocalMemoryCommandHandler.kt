// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Handles lightweight slash-style local memory commands before normal OpenAI turn processing.
 *
 * This keeps session-memory command parsing out of [OpenAIService] while preserving the exact
 * user-visible command set and side effects.
 */
class LocalMemoryCommandHandler(
    private val project: Project,
    private val resetThreadStatePreservingHistory: () -> Unit,
    private val compactConversationWithBrief: (String) -> Unit,
) {
    fun handle(text: String): Boolean {
        val raw = text.trim()
        val normalized = raw.lowercase()
        val memory = project.service<SessionMemoryService>()
        return when {
            normalized == "refresh summary" || normalized == "/refresh summary" -> {
                memory.refreshFromCurrentState(reason = "user_command_refresh", userText = raw, force = true)
                true
            }

            normalized == "compact with memory" || normalized == "/compact with memory" -> {
                val brief = memory.compactConversationHistory()
                resetThreadStatePreservingHistory()
                runCatching { compactConversationWithBrief(brief) }
                true
            }

            normalized == "show session brief" || normalized == "/show session brief" -> {
                true
            }

            normalized == "restore from session memory" || normalized == "/restore from session memory" -> {
                memory.refreshFromCurrentState(
                    reason = "user_command_restore",
                    explicitNote = "Restored state from persisted session memory.",
                    force = true,
                )
                resetThreadStatePreservingHistory()
                true
            }

            normalized.startsWith("pin fact ") || normalized.startsWith("/pin fact ") -> {
                val parsed =
                    parseMemoryFactCommand(if (normalized.startsWith("/pin fact ")) "/pin fact" else "pin fact", raw)
                if (parsed != null) {
                    memory.pinFact(parsed.first, parsed.second)
                }
                true
            }

            normalized.startsWith("mark root cause ") || normalized.startsWith("/mark root cause ") -> {
                val parsed =
                    parseMemoryFactCommand(
                        if (normalized.startsWith("/mark root cause ")) "/mark root cause" else "mark root cause",
                        raw,
                    )
                if (parsed != null) {
                    memory.markRootCause(parsed.first, parsed.second)
                }
                true
            }

            else -> false
        }
    }

    private fun parseMemoryFactCommand(prefix: String, text: String): Pair<String, String?>? {
        val body = text.trim().removePrefix(prefix).trim()
        if (body.isBlank()) return null
        val parts = body.split("| supersedes ", limit = 2)
        val fact = parts[0].trim()
        val supersedes = parts.getOrNull(1)?.trim()?.ifBlank { null }
        if (fact.isBlank()) return null
        return fact to supersedes
    }
}
