// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.github.quanta_dance.quanta.plugins.intellij.tools.agent.AgentCreateTool
import com.github.quanta_dance.quanta.plugins.intellij.tools.agent.AgentRemoveTool
import com.github.quanta_dance.quanta.plugins.intellij.tools.agent.AgentSendMessageTool
import com.github.quanta_dance.quanta.plugins.intellij.tools.builder.GradleSyncTool
import com.github.quanta_dance.quanta.plugins.intellij.tools.catalog.ListToolsCatalogTool
import com.github.quanta_dance.quanta.plugins.intellij.tools.go.RunGoTestsTool
import com.github.quanta_dance.quanta.plugins.intellij.tools.ide.CopyFileOrDirectoryTool
import com.github.quanta_dance.quanta.plugins.intellij.tools.ide.CreateOrUpdateFile
import com.github.quanta_dance.quanta.plugins.intellij.tools.ide.DeleteFileTool
import com.github.quanta_dance.quanta.plugins.intellij.tools.ide.GetFileReferencesAndDependencies
import com.github.quanta_dance.quanta.plugins.intellij.tools.ide.InspectDependencies
import com.github.quanta_dance.quanta.plugins.intellij.tools.ide.ListFiles
import com.github.quanta_dance.quanta.plugins.intellij.tools.ide.OpenFileInEditorTool
import com.github.quanta_dance.quanta.plugins.intellij.tools.ide.PatchFile
import com.github.quanta_dance.quanta.plugins.intellij.tools.ide.ReadFileContent
import com.github.quanta_dance.quanta.plugins.intellij.tools.ide.ReadPsiBlockAtPosition
import com.github.quanta_dance.quanta.plugins.intellij.tools.ide.ValidateClassFileTool
import com.github.quanta_dance.quanta.plugins.intellij.tools.mcp.McpListServerToolsTool
import com.github.quanta_dance.quanta.plugins.intellij.tools.mcp.McpListServersTool
import com.github.quanta_dance.quanta.plugins.intellij.tools.media.GenerateImage
import com.github.quanta_dance.quanta.plugins.intellij.tools.media.SoundGeneratorTool
import com.github.quanta_dance.quanta.plugins.intellij.tools.project.GetProjectDetails
import com.github.quanta_dance.quanta.plugins.intellij.tools.project.SearchInFiles
import com.github.quanta_dance.quanta.plugins.intellij.tools.project.SearchProjectEmbeddings
import com.github.quanta_dance.quanta.plugins.intellij.tools.project.UpsertProjectEmbedding
import com.github.quanta_dance.quanta.plugins.intellij.tools.refactor.CodeRefactorSuggester
import com.github.quanta_dance.quanta.plugins.intellij.tools.system.RequestModelSwitch
import com.github.quanta_dance.quanta.plugins.intellij.tools.system.TerminalCommandTool
import com.intellij.openapi.project.Project
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

object ToolsRegistry {
    enum class Group { GENERIC, GRADLE, GO }

    data class ToolEntry(val clazz: Class<out ToolInterface<out Any>>, val group: Group = Group.GENERIC)

    private data class CacheEntry(val signature: String, val tools: List<Class<out ToolInterface<out Any>>>)

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

    private fun baseEntries(project: Project?): List<ToolEntry> {
        val settings = QuantaAISettingsState.instance.state
        val agentic = settings.agenticEnabled ?: true
        val terminalEnabled = settings.terminalToolEnabled == true
        val list =
            mutableListOf(
                ToolEntry(ListToolsCatalogTool::class.java, Group.GENERIC),
                // Core
                ToolEntry(CodeRefactorSuggester::class.java, Group.GENERIC),
                ToolEntry(CreateOrUpdateFile::class.java, Group.GENERIC),
                ToolEntry(SearchProjectEmbeddings::class.java, Group.GENERIC),
                ToolEntry(UpsertProjectEmbedding::class.java, Group.GENERIC),
                ToolEntry(SearchInFiles::class.java, Group.GENERIC),
                ToolEntry(GetProjectDetails::class.java, Group.GENERIC),
                ToolEntry(ReadFileContent::class.java, Group.GENERIC),
                ToolEntry(ReadPsiBlockAtPosition::class.java, Group.GENERIC),
                ToolEntry(ListFiles::class.java, Group.GENERIC),
                ToolEntry(GetFileReferencesAndDependencies::class.java, Group.GENERIC),
                ToolEntry(GenerateImage::class.java, Group.GENERIC),
                ToolEntry(SoundGeneratorTool::class.java, Group.GENERIC),
                ToolEntry(DeleteFileTool::class.java, Group.GENERIC),
                ToolEntry(CopyFileOrDirectoryTool::class.java, Group.GENERIC),
                ToolEntry(ValidateClassFileTool::class.java, Group.GENERIC),
                ToolEntry(OpenFileInEditorTool::class.java, Group.GENERIC),
                ToolEntry(PatchFile::class.java, Group.GENERIC),
                ToolEntry(RequestModelSwitch::class.java, Group.GENERIC),
                ToolEntry(McpListServersTool::class.java, Group.GENERIC),
                ToolEntry(McpListServerToolsTool::class.java, Group.GENERIC),
            )
        if (terminalEnabled) list.add(ToolEntry(TerminalCommandTool::class.java, Group.GENERIC))
        list.add(ToolEntry(GradleSyncTool::class.java, Group.GRADLE))
        list.add(ToolEntry(RunGoTestsTool::class.java, Group.GO))
        if (agentic) {
            list.add(ToolEntry(AgentCreateTool::class.java, Group.GENERIC))
            list.add(ToolEntry(AgentSendMessageTool::class.java, Group.GENERIC))
            list.add(ToolEntry(AgentRemoveTool::class.java, Group.GENERIC))
        }
        if (javaPsiAvailable(project)) list.add(ToolEntry(InspectDependencies::class.java, Group.GENERIC))
        return list
    }

    fun toolsFor(project: Project): List<Class<out ToolInterface<out Any>>> {
        val settings = QuantaAISettingsState.instance.state
        val agentic = settings.agenticEnabled ?: true
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
                append("terminal=").append(settings.terminalToolEnabled == true).append(';')
                append("base=").append(basePath ?: "<none>")
            }
        val cached = cache[project]
        if (cached != null && cached.signature == signature) return cached.tools

        val entries = baseEntries(project)
        val tools =
            if (basePath == null) {
                entries.map { it.clazz }
            } else {
                entries.filter { e ->
                    when (e.group) {
                        Group.GENERIC -> true
                        Group.GRADLE -> gradle
                        Group.GO -> go
                    }
                }.map { it.clazz }
            }
        cache[project] = CacheEntry(signature, tools)
        return tools
    }

    private fun detectGradle(root: File): Boolean {
        return File(root, "gradlew").exists() || File(root, "gradlew.bat").exists() ||
            File(root, "build.gradle").exists() || File(root, "build.gradle.kts").exists()
    }

    private fun detectGo(root: File): Boolean {
        if (File(root, "go.mod").exists()) return true
        val maxDirs = 5000
        val dq: ArrayDeque<File> = ArrayDeque()
        dq.add(root)
        var visited = 0
        while (dq.isNotEmpty() && visited < maxDirs) {
            val dir = dq.removeFirst()
            visited++
            if (!dir.isDirectory) continue
            val mod = File(dir, "go.mod")
            if (mod.exists()) return true
            val children = dir.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    val name = c.name.lowercase()
                    if (name == ".git" || name == ".idea" || name == "build" || name == "out" || name == "node_modules") continue
                    dq.addLast(c)
                }
            }
        }
        val dirs = listOf(root, File(root, "cmd"), File(root, "pkg"), File(root, "internal"))
        return dirs.any {
                dir ->
            dir.exists() && dir.isDirectory && dir.listFiles()?.any { it.isFile && it.extension.equals("go", true) } == true
        }
    }
}
