package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants

@JsonClassDescription("Tool to synchronize Gradle projects with the current IntelliJ project.")
class GradleSyncTool : ToolInterface<String> {

    var dummy: Boolean = true

    override fun execute(project: Project): String {
        val gradleSettings = GradleSettings.getInstance(project)
        val projectSettings = gradleSettings.linkedProjectsSettings.firstOrNull { settings ->
            FileUtil.pathsEqual(settings.externalProjectPath, project.basePath)
        } as? GradleProjectSettings ?: return "no Gradle projects"

        // Trigger the import of the project
        ExternalSystemUtil.refreshProject(
            project,
            GradleConstants.SYSTEM_ID,
            project.basePath.toString(),
            true,
            ProgressExecutionMode.IN_BACKGROUND_ASYNC
        )
        return "Sync Gradle projects in background"
    }
}