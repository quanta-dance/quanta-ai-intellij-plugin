// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.intellij.openapi.project.Project
import java.io.File
import java.util.ArrayDeque

object ToolsRegistry {
    enum class Group { GENERIC, GRADLE, GO }

    data class ToolEntry(val clazz: Class<out ToolInterface<out Any>>, val group: Group = Group.GENERIC)

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
        val list =
            mutableListOf(
                ToolEntry(CodeRefactorSuggester::class.java, Group.GENERIC),
                ToolEntry(CreateOrUpdateFile::class.java, Group.GENERIC),
                ToolEntry(SearchProjectEmbeddings::class.java, Group.GENERIC),
                ToolEntry(UpsertProjectEmbedding::class.java, Group.GENERIC),
                ToolEntry(SearchInFiles::class.java, Group.GENERIC),
                ToolEntry(GetProjectDetails::class.java, Group.GENERIC),
                ToolEntry(ReadFileContent::class.java, Group.GENERIC),
                ToolEntry(ListFiles::class.java, Group.GENERIC),
                ToolEntry(GetFileReferencesAndDependencies::class.java, Group.GENERIC),
                ToolEntry(GenerateImage::class.java, Group.GENERIC),
                ToolEntry(SoundGeneratorTool::class.java, Group.GENERIC),
                ToolEntry(DeleteFileTool::class.java, Group.GENERIC),
                ToolEntry(CopyFileOrDirectoryTool::class.java, Group.GENERIC),
                ToolEntry(ValidateClassFileTool::class.java, Group.GENERIC),
                // Gradle
                ToolEntry(TerminalCommandTool::class.java, Group.GENERIC),
                ToolEntry(RunGradleTestsTool::class.java, Group.GRADLE),
                ToolEntry(GetTestInfoTool::class.java, Group.GRADLE),
                // Editor/file
                ToolEntry(OpenFileInEditorTool::class.java, Group.GENERIC),
                ToolEntry(PatchFile::class.java, Group.GENERIC),
                // Go
                ToolEntry(RunGoTestsTool::class.java, Group.GO),
                // Dynamic model tool
                ToolEntry(RequestModelSwitch::class.java, Group.GENERIC),
            )
        if (javaPsiAvailable(project)) {
            list.add(ToolEntry(InspectDependencies::class.java, Group.GENERIC))
        }
        return list
    }

    fun toolsFor(project: Project): List<Class<out ToolInterface<out Any>>> {
        val entries = baseEntries(project)
        val basePath = project.basePath ?: return entries.map { it.clazz }
        val root = File(basePath)
        val hasGradle = detectGradle(root)
        val hasGo = detectGo(root)
        return entries.filter { e ->
            when (e.group) {
                Group.GENERIC -> true
                Group.GRADLE -> hasGradle
                Group.GO -> hasGo
            }
        }.map { it.clazz }
    }

    private fun detectGradle(root: File): Boolean {
        return File(root, "gradlew").exists() || File(root, "gradlew.bat").exists() ||
            File(root, "build.gradle").exists() || File(root, "build.gradle.kts").exists()
    }

    private fun detectGo(root: File): Boolean {
        // 1) Direct module in root
        if (File(root, "go.mod").exists()) return true
        // 2) BFS search for go.mod in subdirs (bounded)
        val maxDirs = 5000
        val dq: ArrayDeque<File> = ArrayDeque()
        dq.add(root)
        var visited = 0
        while (dq.isNotEmpty() && visited < maxDirs) {
            val dir = dq.removeFirst()
            visited++
            if (!dir.isDirectory) continue
            // Fast path: go.mod
            val mod = File(dir, "go.mod")
            if (mod.exists()) return true
            // Enqueue children dirs (skip hidden and VCS folders)
            val children = dir.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    val name = c.name.lowercase()
                    if (name == ".git" || name == ".idea" || name == "build" || name == "out" || name == "node_modules") continue
                    dq.addLast(c)
                }
            }
        }
        // 3) Heuristic fallback: look for .go files in common folders
        val dirs = listOf(root, File(root, "cmd"), File(root, "pkg"), File(root, "internal"))
        return dirs.any {
                dir ->
            dir.exists() && dir.isDirectory && dir.listFiles()?.any { it.isFile && it.extension.equals("go", true) } == true
        }
    }
}
