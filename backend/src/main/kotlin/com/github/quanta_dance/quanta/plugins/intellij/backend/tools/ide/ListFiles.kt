// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.project.Project
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

@JsonClassDescription("Read list of files in the requested directory")
/**
 * Backend tool that lists directory entries within the project root.
 *
 * It resolves the requested path through [PathUtils] so callers stay inside the project boundary
 * even in split-mode or remote environments.
 */
class ListFiles : ToolInterface<List<String>> {
    @field:JsonPropertyDescription("Relative to the project root path to the file list.")
    var path: String? = null

    override fun execute(project: Project): List<String> {
        val projBase = PathUtils.projectRootPath(project) ?: return emptyList()
        return try {
            val absPath = PathUtils.resolveWithinProject(projBase, path, allowBlankAsDot = true)
            if (absPath.exists() && absPath.isDirectory()) {
                return absPath.listDirectoryEntries().map { entry ->
                    PathUtils.relativizeToProject(projBase, entry)
                }
            }
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
