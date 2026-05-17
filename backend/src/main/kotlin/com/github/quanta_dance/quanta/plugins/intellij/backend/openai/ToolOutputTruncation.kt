// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.fasterxml.jackson.databind.ObjectMapper

private val toolOutputMapper = ObjectMapper()

fun truncateToolOutput(
    value: Any?,
    maxJsonChars: Int = 4000,
    maxStringChars: Int = 2000,
    depth: Int = 0,
): Any? {
    if (value == null) return null
    if (depth > 6) return "<truncated: max depth reached>"

    val simplified: Any =
        when (value) {
            is String -> {
                if (value.length <= maxStringChars) value else value.take(maxStringChars) + "... (truncated)"
            }

            is Map<*, *> -> {
                value.entries
                    .take(80)
                    .associate { (k, v) ->
                        val key = k?.toString() ?: "<null>"
                        key to truncateToolOutput(v, maxJsonChars, maxStringChars, depth + 1)
                    }
            }

            is List<*> -> {
                value.take(80).map { truncateToolOutput(it, maxJsonChars, maxStringChars, depth + 1) }
            }

            else -> {
                value
            }
        }

    return try {
        val json = toolOutputMapper.writeValueAsString(simplified)
        if (json.length <= maxJsonChars) {
            simplified
        } else {
            mapOf(
                "truncated" to true,
                "preview" to json.take(maxJsonChars) + "... (truncated)",
                "originalChars" to json.length,
            )
        }
    } catch (_: Throwable) {
        simplified
    }
}
