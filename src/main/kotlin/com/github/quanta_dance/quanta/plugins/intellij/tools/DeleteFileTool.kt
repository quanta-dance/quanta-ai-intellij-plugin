// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

@JsonClassDescription(
    "Tool to delete a specified file from the project. " +
        "Before modifying methods in the file you may need to check for this method references as they might need to be updated.",
)
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
        val base = project.basePath ?: return "Project path not found."
        if (!confirmed) return "Deletion not confirmed. Set 'confirmed' to true to proceed."

        val target =
            try {
                PathUtils.resolveWithinProject(base, filePath)
            } catch (e: IllegalArgumentException) {
                project.service<ToolWindowService>()
                    .addToolingMessage("File delete - rejected", e.message ?: "Invalid path")
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
            val rel = PathUtils.relativizeToProject(base, target)
            project.service<ToolWindowService>().addToolingMessage("File deleted", rel)
            "Delete successful"
        } catch (e: Exception) {
            val msg = "Error deleting: ${e.message}"
            project.service<ToolWindowService>().addToolingMessage("File delete - error", msg)
            msg
        }
    }
}
