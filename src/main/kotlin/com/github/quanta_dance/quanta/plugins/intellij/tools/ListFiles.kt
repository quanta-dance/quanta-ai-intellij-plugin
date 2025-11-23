package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

@JsonClassDescription("Read list of files in the requested directory")
class ListFiles : ToolInterface<List<String>> {

    @JsonPropertyDescription("Relative to the project root path to the file list.")
    var path: String? = null

    override fun execute(project: Project): List<String> {
        val projBase = project.basePath ?: return emptyList()
        return try {
            project.service<ToolWindowService>().addToolingMessage("List Files", path.orEmpty())
            val absPath = PathUtils.resolveWithinProject(projBase, path, allowBlankAsDot = true)
            if (absPath.exists() && absPath.isDirectory()) {
                val basePath = projBase
                return absPath.listDirectoryEntries().map { entry ->
                    PathUtils.relativizeToProject(basePath, entry)
                }
            }
            emptyList()
        } catch (e: IllegalArgumentException) {
            project.service<ToolWindowService>().addToolingMessage("List Files - rejected", e.message ?: "Invalid path")
            emptyList()
        } catch (e: Throwable) {
            project.service<ToolWindowService>().addToolingMessage("List Files - error", e.message ?: "Unknown error")
            emptyList()
        }
    }
}
