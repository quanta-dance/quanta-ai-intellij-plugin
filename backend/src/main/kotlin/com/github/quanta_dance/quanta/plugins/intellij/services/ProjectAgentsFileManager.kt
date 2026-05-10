// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Minimal adapter that exposes repository-root AGENTS.md as the canonical project context file.
 * This class is intentionally small: it only reads AGENTS.md (if present) and returns its
 * contents bounded by maxChars. It does not attempt to generate or write the file.
 *
 * Rationale: adding a small adapter reduces risk vs editing the existing ProjectContextFileManager
 * in-place. Callers that need AGENTS.md can be updated to use this adapter incrementally.
 */
class ProjectAgentsFileManager(
    private val project: Project,
) {
    companion object {
        private val log = Logger.getInstance(ProjectAgentsFileManager::class.java)
        private const val FILE_NAME = "AGENTS.md"
    }

    private fun projectRoot(): Path? = PathUtils.projectRootPath(project)?.let { Path.of(it) }

    fun readAgentsFile(maxChars: Int = 16_000): String {
        val root = projectRoot() ?: return ""
        val file = root.resolve(FILE_NAME)
        return try {
            if (!Files.exists(file)) return ""
            val text = Files.readString(file, StandardCharsets.UTF_8)
            if (text.length <= maxChars) text else text.take(maxChars) + "\n\n... (truncated)"
        } catch (t: Throwable) {
            log.warn("Failed to read $FILE_NAME", t)
            ""
        }
    }
}
