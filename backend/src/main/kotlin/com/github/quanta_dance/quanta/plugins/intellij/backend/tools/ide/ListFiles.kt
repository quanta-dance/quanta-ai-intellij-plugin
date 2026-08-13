// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolExecutionPresentation
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolPresentationProvider
import com.intellij.openapi.project.Project
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Backend tool that lists directory entries within the project root.
 *
 * It resolves the requested path through [PathUtils] so callers stay inside the project boundary
 * even in split-mode or remote environments.
 */
@JsonClassDescription("Read list of files in the requested directory")
class ListFiles :
    ToolInterface<ListFiles.Result>,
    ToolPresentationProvider {
    override val canBeParallel: Boolean = true

    override fun presentation(status: ToolExecutionStatus): ToolExecutionPresentation =
        ToolExecutionPresentation(
            title = path?.trim()?.takeIf { it.isNotBlank() }?.let { "Listing files in $it" } ?: "Listing files",
        )

    @JsonClassDescription("ListFiles operation result")
    data class Result(
        @field:JsonPropertyDescription("Requested directory path relative to project root. Blank means project root.")
        val requestedPath: String,
        @field:JsonPropertyDescription("Resolved directory path relative to project root when successful.")
        val resolvedPath: String,
        @field:JsonPropertyDescription("Directory entries relative to project root.")
        val entries: List<String>,
        @field:JsonPropertyDescription("Error message if listing failed.")
        val error: String = "",
    )

    @field:JsonPropertyDescription("Relative to the project root path to the file list.")
    var path: String? = null

    override fun execute(project: Project): Result {
        val requested = path?.trim().orEmpty()
        val projBase =
            PathUtils.projectRootPath(project)
                ?: return Result(
                    requestedPath = requested,
                    resolvedPath = "",
                    entries = emptyList(),
                    error = "Project base path not found.",
                )

        return try {
            val absPath = PathUtils.resolveWithinProject(projBase, path, allowBlankAsDot = true)
            val resolved = PathUtils.relativizeToProject(projBase, absPath)
            when {
                !absPath.exists() -> {
                    Result(
                        requestedPath = requested,
                        resolvedPath = resolved,
                        entries = emptyList(),
                        error = "Directory not found: ${if (requested.isBlank()) "." else requested}",
                    )
                }

                !absPath.isDirectory() -> {
                    Result(
                        requestedPath = requested,
                        resolvedPath = resolved,
                        entries = emptyList(),
                        error = "Requested path is not a directory: ${if (requested.isBlank()) "." else requested}",
                    )
                }

                else -> {
                    Result(
                        requestedPath = requested,
                        resolvedPath = resolved,
                        entries =
                            absPath
                                .listDirectoryEntries()
                                .map { entry -> PathUtils.relativizeToProject(projBase, entry) },
                    )
                }
            }
        } catch (e: IllegalArgumentException) {
            Result(
                requestedPath = requested,
                resolvedPath = "",
                entries = emptyList(),
                error = e.message ?: "Invalid path",
            )
        } catch (e: Throwable) {
            Result(
                requestedPath = requested,
                resolvedPath = "",
                entries = emptyList(),
                error = e.message ?: "Failed to list directory entries",
            )
        }
    }
}
