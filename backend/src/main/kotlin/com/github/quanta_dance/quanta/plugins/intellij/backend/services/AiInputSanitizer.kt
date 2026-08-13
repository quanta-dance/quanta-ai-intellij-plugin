// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Sanitizes user- or context-provided text before it is sent to the AI.
 *
 * Visible chat content is intentionally left unchanged; this sanitizer is only for the AI-bound
 * request layer so local machine details do not leak into model context unnecessarily.
 */
class AiInputSanitizer(
    private val project: Project,
) {
    private val shellPromptRegex = Regex("(?m)^\\s*[^\\s@]+@[^\\s]+\\s+[^\\s]+\\s+([%#$])\\s+")

    fun sanitizeForAi(text: String): String {
        if (text.isBlank()) return text
        return text
            .let(::sanitizeProjectAbsolutePaths)
            .let(::sanitizeShellPromptPrefixes)
    }

    private fun sanitizeProjectAbsolutePaths(text: String): String {
        val projectRoot = project.basePath?.trim()?.takeIf { it.isNotEmpty() } ?: return text
        val normalizedRoot = runCatching { Path.of(projectRoot).normalize() }.getOrNull() ?: return text
        val normalizedRootText = normalizedRoot.toString().replace('\\', '/')
        if (normalizedRootText.isBlank()) return text

        var sanitized = text
        sanitized = sanitized.replace(normalizedRootText + "/", "")
        sanitized = sanitized.replace(normalizedRootText, ".")

        val systemRootPrefix = normalizedRoot.root?.toString()?.replace('\\', '/')
        if (!systemRootPrefix.isNullOrBlank()) {
            sanitized = sanitized.replace(systemRootPrefix + "./", systemRootPrefix)
        }
        return sanitized
    }

    private fun sanitizeShellPromptPrefixes(text: String): String =
        text.replace(shellPromptRegex) { matchResult ->
            "${matchResult.groupValues[1]} "
        }
}
