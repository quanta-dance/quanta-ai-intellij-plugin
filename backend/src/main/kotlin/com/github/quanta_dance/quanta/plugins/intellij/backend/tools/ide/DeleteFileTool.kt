// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

@JsonClassDescription(
    "Tool to delete a specified file from the project. " +
            "Before modifying methods in the file you may need to check for this method references as they might need to be updated.",
)
/**
 * Backend tool for guarded file or directory deletion within the project root.
 *
 * It requires an explicit confirmation flag so destructive operations are never performed by
 * accident in autonomous flows.
 */
class DeleteFileTool : ToolInterface<String> {
    @field:JsonPropertyDescription("Relative to the project root path to the file to be deleted.")
    var filePath: String? = null

    @field:JsonPropertyDescription("Must be true to perform deletion. Default false to prevent accidental deletes.")
    var confirmed: Boolean = false

    @field:JsonPropertyDescription("Delete directories recursively if true. Default false (will fail on non-empty directories).")
    var recursive: Boolean = false

    private fun deleteRecursively(path: Path) {
        // Walk from leaves to root
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    override fun execute(project: Project): String {
        val base = PathUtils.projectRootPath(project) ?: return "Project path not found."
        if (!confirmed) return "Deletion not confirmed. Set 'confirmed' to true to proceed."

        val target =
            try {
                PathUtils.resolveWithinProject(base, filePath)
            } catch (e: IllegalArgumentException) {
                return e.message ?: "Invalid path"
            }

        return try {
            if (Files.isDirectory(target)) {
                if (recursive) {
                    deleteRecursively(target)
                } else {
                    Files.delete(target) // Will throw if non-empty
                }
            } else {
                Files.deleteIfExists(target)
            }
            "Delete successful"
        } catch (e: Exception) {
            val msg = "Error deleting: ${e.message}"
            msg
        }
    }
}
