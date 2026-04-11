// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools

import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

object ToolsRegistry {
    private data class CacheEntry(
        val signature: String,
        val tools: List<Class<out ToolInterface<out Any>>>,
    )

    private val cache = ConcurrentHashMap<Project, CacheEntry>()

    private fun classAvailable(name: String, project: Project?): Boolean {
        fun tryLoad(loader: ClassLoader?): Boolean =
            try {
                loader?.loadClass(name)
                true
            } catch (_: Throwable) {
                false
            }
        if (tryLoad(this::class.java.classLoader)) return true
        if (project != null && tryLoad(project::class.java.classLoader)) return true
        return try {
            Class.forName(name)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun loadTool(name: String): Class<out ToolInterface<out Any>>? =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            Class.forName(name) as Class<out ToolInterface<out Any>>
        }.getOrNull()

    private fun computeSignature(project: Project): String =
        listOf(
            "javaPsi=${classAvailable("com.intellij.psi.JavaPsiFacade", project)}",
            "gradle=${classAvailable("org.jetbrains.plugins.gradle.util.GradleConstants", project)}",
            "go=${classAvailable("com.github.quanta_dance.quanta.plugins.intellij.tools.go.RunGoTestsTool", project)}",
        ).joinToString("|")

    fun toolsFor(project: Project): List<Class<out ToolInterface<out Any>>> {
        val sig = computeSignature(project)
        cache[project]?.takeIf { it.signature == sig }?.let { return it.tools }

        val names = mutableListOf<String>()
        names += listOf(
            "com.github.quanta_dance.quanta.plugins.intellij.tools.project.GetProjectDetails",
            "com.github.quanta_dance.quanta.plugins.intellij.tools.project.SearchInFiles",
            "com.github.quanta_dance.quanta.plugins.intellij.tools.project.SearchProjectEmbeddings",
            "com.github.quanta_dance.quanta.plugins.intellij.tools.project.UpsertProjectEmbedding",
            "com.github.quanta_dance.quanta.plugins.intellij.backend.tools.catalog.ListToolsCatalogTool",
            "com.github.quanta_dance.quanta.plugins.intellij.tools.media.GenerateImage",
            "com.github.quanta_dance.quanta.plugins.intellij.tools.media.SoundGeneratorTool",
            "com.github.quanta_dance.quanta.plugins.intellij.tools.session.SessionPlanTool",
            "com.github.quanta_dance.quanta.plugins.intellij.tools.session.ScheduleTaskTool",
            "com.github.quanta_dance.quanta.plugins.intellij.tools.system.RequestModelSwitch",
            "com.github.quanta_dance.quanta.plugins.intellij.tools.system.TerminalCommandTool",
            "com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp.McpListServersTool",
            "com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp.McpListServerToolsTool",
        )
        if (classAvailable("com.intellij.psi.JavaPsiFacade", project)) {
            names += listOf(
                "com.github.quanta_dance.quanta.plugins.intellij.tools.ide.CreateOrUpdateFile",
                "com.github.quanta_dance.quanta.plugins.intellij.tools.ide.OpenFileInEditorTool",
                "com.github.quanta_dance.quanta.plugins.intellij.tools.ide.CopyFileOrDirectoryTool",
                "com.github.quanta_dance.quanta.plugins.intellij.tools.ide.DeleteFileTool",
                "com.github.quanta_dance.quanta.plugins.intellij.tools.ide.ListFiles",
                "com.github.quanta_dance.quanta.plugins.intellij.tools.ide.ReadFileContent",
                "com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile",
                "com.github.quanta_dance.quanta.plugins.intellij.tools.ide.ValidateClassFileTool",
                "com.github.quanta_dance.quanta.plugins.intellij.tools.refactor.CodeRefactorSuggester",
                "com.github.quanta_dance.quanta.plugins.intellij.tools.ide.ReadPsiBlockAtPositionTool",
                "com.github.quanta_dance.quanta.plugins.intellij.tools.ide.GetFileReferencesAndDependenciesTool",
            )
        }
        if (classAvailable("org.jetbrains.plugins.gradle.util.GradleConstants", project)) {
            names += listOf(
                "com.github.quanta_dance.quanta.plugins.intellij.tools.builder.GetTestInfoTool",
                "com.github.quanta_dance.quanta.plugins.intellij.tools.builder.GradleSyncTool",
                "com.github.quanta_dance.quanta.plugins.intellij.tools.builder.RunGradleBuildTool",
                "com.github.quanta_dance.quanta.plugins.intellij.tools.builder.RunGradleTestsTool",
            )
        }
        if (classAvailable("com.github.quanta_dance.quanta.plugins.intellij.tools.go.RunGoTestsTool", project)) {
            names += "com.github.quanta_dance.quanta.plugins.intellij.tools.go.RunGoTestsTool"
        }
        names += listOf(
            "com.github.quanta_dance.quanta.plugins.intellij.tools.agent.AgentCreateTool",
            "com.github.quanta_dance.quanta.plugins.intellij.tools.agent.AgentRemoveTool",
            "com.github.quanta_dance.quanta.plugins.intellij.tools.agent.AgentSendMessageTool",
            "com.github.quanta_dance.quanta.plugins.intellij.tools.agent.AgentReadInboxTool",
            "com.github.quanta_dance.quanta.plugins.intellij.tools.agent.AgentPostMessageTool",
        )

        val list = names.mapNotNull(::loadTool)
        cache[project] = CacheEntry(sig, list)
        return list
    }
}
