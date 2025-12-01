// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@JsonClassDescription(
    "Tool to copy a specified file or directory within the project. " +
        "Before modifying methods in the file you may need to check for this method references as they might need to be updated.",
)
class CopyFileOrDirectoryTool : ToolInterface<String> {
    @JsonPropertyDescription("Source path of the file or directory to be copied.")
    var sourcePath: String? = null

    @JsonPropertyDescription("Destination path where the file or directory should be copied.")
    var destinationPath: String? = null

    @JsonPropertyDescription("Overwrite existing files if true. Default false.")
    var overwriteExisting: Boolean = false

    @JsonPropertyDescription("Copy file attributes if true. Default false.")
    var copyAttributes: Boolean = false

    override fun execute(project: Project): String {
        val base = project.basePath ?: return "Project base path not found."
        val source =
            try {
                PathUtils.resolveWithinProject(base, sourcePath)
            } catch (e: IllegalArgumentException) {
                project.service<ToolWindowService>().addToolingMessage("Copy - rejected", e.message ?: "Invalid source path")
                return e.message ?: "Invalid source path"
            }
        val destination =
            try {
                PathUtils.resolveWithinProject(base, destinationPath)
            } catch (e: IllegalArgumentException) {
                project.service<ToolWindowService>().addToolingMessage("Copy - rejected", e.message ?: "Invalid destination path")
                return e.message ?: "Invalid destination path"
            }

        // Prevent copying into itself or into a child of itself
        val sourceNorm = source.toAbsolutePath().normalize()
        val destNorm = destination.toAbsolutePath().normalize()
        if (destNorm == sourceNorm || destNorm.startsWith(sourceNorm)) {
            return "Destination cannot be the same as source or inside the source directory."
        }

        val copyOptions = mutableListOf<StandardCopyOption>()
        if (overwriteExisting) copyOptions.add(StandardCopyOption.REPLACE_EXISTING)
        if (copyAttributes) copyOptions.add(StandardCopyOption.COPY_ATTRIBUTES)

        return try {
            if (Files.isDirectory(source)) {
                Files.createDirectories(destNorm)
                Files.walk(source).forEach { path ->
                    val target = destNorm.resolve(source.relativize(path))
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        if (copyOptions.isEmpty()) {
                            Files.copy(path, target)
                        } else {
                            Files.copy(path, target, *copyOptions.toTypedArray())
                        }
                    }
                }
            } else {
                val finalTarget = if (Files.exists(destNorm) && Files.isDirectory(destNorm)) destNorm.resolve(source.fileName) else destNorm
                Files.createDirectories(finalTarget.parent)
                if (copyOptions.isEmpty()) {
                    Files.copy(source, finalTarget)
                } else {
                    Files.copy(source, finalTarget, *copyOptions.toTypedArray())
                }
            }
            val relMsg = PathUtils.relativizeToProject(base, destNorm)
            project.service<ToolWindowService>().addToolingMessage("Copy successful", "$sourcePath -> $relMsg")
            "Copy successful"
        } catch (e: Exception) {
            val msg = "Error copying: ${e.message}"
            project.service<ToolWindowService>().addToolingMessage("Copy - error", msg)
            msg
        }
    }
}
