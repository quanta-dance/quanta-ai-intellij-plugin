// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.catalog.ListToolsCatalogTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide.CopyFileOrDirectoryTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide.CreateOrUpdateFile
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide.DeleteFileTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide.GetFileReferencesAndDependencies
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide.ListFiles
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide.OpenFileInEditorTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide.PatchFile
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide.ReadFile
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide.ReadPsiBlockAtPosition
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide.ValidateClassFileTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp.McpListServerToolsTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp.McpListServersTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.project.GetProjectDetails
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.project.SearchInFiles
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.refactor.CodeRefactorSuggester
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.session.ScheduleTaskTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.session.SessionPlanTool
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.system.RequestModelSwitch
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.system.TerminalCommandTool
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Central registry for agent-callable backend tools.
 *
 * The registry groups tools by capability and enables or filters them based on runtime availability
 * such as Gradle support, Java PSI presence, Go support, and plugin settings.
 */
object ToolsRegistry {
    enum class Group { GENERIC, GRADLE, GO }

    data class ToolEntry(
        val clazz: Class<out ToolInterface<out Any>>,
        val group: Group = Group.GENERIC,
    )

    private data class CacheEntry(
        val signature: String,
        val tools: List<Class<out ToolInterface<out Any>>>,
    )

    private val cache = ConcurrentHashMap<Project, CacheEntry>()

    private fun javaPsiAvailable(project: Project?): Boolean {
        fun tryLoad(loader: ClassLoader?): Boolean =
            try {
                loader?.loadClass("com.intellij.psi.JavaPsiFacade")
                true
            } catch (_: Throwable) {
                false
            }
        if (tryLoad(this::class.java.classLoader)) return true
        if (project != null && tryLoad(project::class.java.classLoader)) return true
        return try {
            Class.forName("com.intellij.psi.JavaPsiFacade")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun gradlePluginAvailable(project: Project?): Boolean {
        fun tryLoad(loader: ClassLoader?): Boolean =
            try {
                loader?.loadClass("org.jetbrains.plugins.gradle.util.GradleConstants")
                true
            } catch (_: Throwable) {
                false
            }
        if (tryLoad(this::class.java.classLoader)) return true
        if (project != null && tryLoad(project::class.java.classLoader)) return true
        return try {
            Class.forName("org.jetbrains.plugins.gradle.util.GradleConstants")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun baseEntries(project: Project?): List<ToolEntry> {
        val runtimeSettings = BackendRuntimeSettingsService.instance.settings
        val agentic = runtimeSettings.agenticEnabled ?: true
        val terminalEnabled = runtimeSettings.terminalToolEnabled == true
        val list =
            mutableListOf(
                ToolEntry(ListToolsCatalogTool::class.java, Group.GENERIC),
                ToolEntry(GetProjectDetails::class.java, Group.GENERIC),
                ToolEntry(SearchInFiles::class.java, Group.GENERIC),
                ToolEntry(CodeRefactorSuggester::class.java, Group.GENERIC),
                ToolEntry(CreateOrUpdateFile::class.java, Group.GENERIC),
                ToolEntry(ReadFile::class.java, Group.GENERIC),
                ToolEntry(ReadPsiBlockAtPosition::class.java, Group.GENERIC),
                ToolEntry(ListFiles::class.java, Group.GENERIC),
                ToolEntry(GetFileReferencesAndDependencies::class.java, Group.GENERIC),
                ToolEntry(OpenFileInEditorTool::class.java, Group.GENERIC),
                ToolEntry(PatchFile::class.java, Group.GENERIC),
                ToolEntry(DeleteFileTool::class.java, Group.GENERIC),
                ToolEntry(CopyFileOrDirectoryTool::class.java, Group.GENERIC),
                ToolEntry(ValidateClassFileTool::class.java, Group.GENERIC),
                ToolEntry(RequestModelSwitch::class.java, Group.GENERIC),
                ToolEntry(McpListServersTool::class.java, Group.GENERIC),
                ToolEntry(McpListServerToolsTool::class.java, Group.GENERIC),
                ToolEntry(SessionPlanTool::class.java, Group.GENERIC),
                ToolEntry(ScheduleTaskTool::class.java, Group.GENERIC),
                ToolEntry(
                    com.github.quanta_dance.quanta.plugins.intellij.backend.tools.go.RunGoTestsTool::class.java,
                    Group.GO,
                ),
            )

        if (terminalEnabled) list.add(ToolEntry(TerminalCommandTool::class.java, Group.GENERIC))
        list.add(
            ToolEntry(
                com.github.quanta_dance.quanta.plugins.intellij.backend.tools.media.GenerateImage::class.java,
                Group.GENERIC,
            ),
        )
        list.add(
            ToolEntry(
                com.github.quanta_dance.quanta.plugins.intellij.backend.tools.media.SoundGeneratorTool::class.java,
                Group.GENERIC,
            ),
        )

        if (agentic) {
            list.add(
                ToolEntry(
                    com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.AgentCreateTool::class.java,
                    Group.GENERIC,
                ),
            )
            list.add(
                ToolEntry(
                    com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.AgentSendMessageTool::class.java,
                    Group.GENERIC,
                ),
            )
            list.add(
                ToolEntry(
                    com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.AgentPostMessageTool::class.java,
                    Group.GENERIC,
                ),
            )
            list.add(
                ToolEntry(
                    com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.AgentReadInboxTool::class.java,
                    Group.GENERIC,
                ),
            )
            list.add(
                ToolEntry(
                    com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent.AgentRemoveTool::class.java,
                    Group.GENERIC,
                ),
            )
        }
        if (javaPsiAvailable(project)) {
            list.add(
                ToolEntry(
                    com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide.InspectDependencies::class.java,
                    Group.GENERIC,
                ),
            )
        }
        return list
    }

    fun toolsFor(project: Project): List<Class<out ToolInterface<out Any>>> {
        val runtimeSettings = BackendRuntimeSettingsService.instance.settings
        val agentic = runtimeSettings.agenticEnabled ?: true
        val basePath = project.basePath
        val gradle = basePath?.let { detectGradle(File(it)) } ?: false
        val go = basePath?.let { detectGo(File(it)) } ?: false
        val javaPsi = javaPsiAvailable(project)
        val signature =
            buildString {
                append("agentic=").append(agentic).append(';')
                append("gradle=").append(gradle).append(';')
                append("go=").append(go).append(';')
                append("javaPsi=").append(javaPsi).append(';')
                append("terminal=").append(runtimeSettings.terminalToolEnabled == true).append(';')
                append("base=").append(basePath ?: "<none>")
            }
        cache[project]?.takeIf { it.signature == signature }?.let { return it.tools }

        val entries = baseEntries(project).toMutableList()
        if (gradle && gradlePluginAvailable(project)) {
            entries.add(
                ToolEntry(
                    com.github.quanta_dance.quanta.plugins.intellij.backend.tools.builder.GradleSyncTool::class.java,
                    Group.GRADLE,
                ),
            )
            entries.add(
                ToolEntry(
                    com.github.quanta_dance.quanta.plugins.intellij.backend.tools.builder.RunGradleBuildTool::class.java,
                    Group.GRADLE,
                ),
            )
            entries.add(
                ToolEntry(
                    com.github.quanta_dance.quanta.plugins.intellij.backend.tools.builder.RunGradleTestsTool::class.java,
                    Group.GRADLE,
                ),
            )
            entries.add(
                ToolEntry(
                    com.github.quanta_dance.quanta.plugins.intellij.backend.tools.builder.GetTestInfoTool::class.java,
                    Group.GRADLE,
                ),
            )
        }
        if (!go) {
            entries.removeIf { it.group == Group.GO }
        }
        val result = entries.map { it.clazz }
        cache[project] = CacheEntry(signature, result)
        return result
    }

    private fun detectGradle(dir: File): Boolean =
        listOf("build.gradle.kts", "settings.gradle.kts", "build.gradle", "settings.gradle")
            .any { File(dir, it).exists() }

    private fun detectGo(dir: File): Boolean {
        if (File(dir, "go.mod").exists()) return true
        return dir.walkTopDown().maxDepth(4).any { it.isDirectory && it.name == "pkg" }
    }
}
