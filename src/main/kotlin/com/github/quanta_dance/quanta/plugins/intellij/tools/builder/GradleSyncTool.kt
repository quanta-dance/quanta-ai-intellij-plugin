package com.github.quanta_dance.quanta.plugins.intellij.tools.builder

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants

@JsonClassDescription("Synchronize linked Gradle projects with the current IntelliJ project (runs in background).")
class GradleSyncTool : ToolInterface<String> {

    @field:JsonPropertyDescription("Optional Gradle project path to sync. If omitted, all linked Gradle projects are synced.")
    var projectPath: String? = null

    @field:JsonPropertyDescription("If true, attempts to refresh dependencies along with project model. Default: true")
    var refreshDependencies: Boolean = true

    override fun execute(project: Project): String {
        val basePath = project.basePath ?: return "Project base path not found."
        val gradleSettings = GradleSettings.getInstance(project)
        var linked = gradleSettings.linkedProjectsSettings.mapNotNull { it.externalProjectPath }.distinct()
        if (!projectPath.isNullOrBlank()) {
            val target = projectPath!!.trim()
            linked = linked.filter { it.equals(target, ignoreCase = true) || it.endsWith(target) }
        }
        if (linked.isEmpty()) {
            project.service<ToolWindowService>().addToolingMessage("Gradle Sync", "No linked Gradle projects found for $basePath")
            return "No linked Gradle projects found."
        }

        linked.forEach { path ->
            ExternalSystemUtil.refreshProject(
                project,
                GradleConstants.SYSTEM_ID,
                path,
                refreshDependencies,
                ProgressExecutionMode.IN_BACKGROUND_ASYNC,
            )
        }
        project.service<ToolWindowService>().addToolingMessage("Gradle Sync", "Queued sync for ${linked.size} project(s) in background")
        return "Queued Gradle sync for ${linked.size} project(s) in background."
    }
}
