// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.project.ProjectVersionUtil
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Builds a compact project-landscape summary for first-turn/reset agent context.
 *
 * This intentionally complements, rather than replaces, the full `GetProjectDetails` tool:
 * it gives fast orientation about the project shape and likely entry points, while the tool remains
 * the deeper on-demand source for full structure and detailed discovery.
 */
class ProjectLandscapeContextBuilder(
    private val project: Project,
) {
    fun buildMessage(): String {
        val sdkVersion = runCatching { ProjectVersionUtil.getProjectCompileVersion(project) }.getOrNull()
        val buildFiles = runCatching { ProjectVersionUtil.getProjectBuildFiles(project) }.getOrNull().orEmpty()
        val root =
            PathUtils
                .projectRootPath(project)
                ?.let { LocalFileSystem.getInstance().findFileByPath(it) }

        val topLevelEntries =
            root
                ?.children
                ?.asSequence()
                ?.filter { it.isValid }
                ?.map { if (it.isDirectory) "${it.name}/" else it.name }
                ?.sorted()
                ?.take(12)
                ?.toList()
                .orEmpty()

        val likelySourceRoots = findLikelySourceRoots(root)

        return buildString {
            append("Project landscape (auto, compact).\n")
            if (buildFiles.isNotEmpty()) {
                append("Build files: ").append(buildFiles.joinToString(", ")).append('\n')
            }
            sdkVersion?.takeIf { it.isNotBlank() }?.let {
                append(it).append('\n')
            }
            if (topLevelEntries.isNotEmpty()) {
                append("Top-level entries: ").append(topLevelEntries.joinToString(", ")).append('\n')
            }
            if (likelySourceRoots.isNotEmpty()) {
                append("Likely source roots: ").append(likelySourceRoots.joinToString(", ")).append('\n')
            }
            append("Use GetProjectDetails when you need the full project structure or deeper discovery.")
        }.trim()
    }

    private fun findLikelySourceRoots(root: VirtualFile?): List<String> {
        if (root == null) return emptyList()
        val candidates = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<VirtualFile, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (!current.isValid || depth > 4) continue

            if (current.isDirectory) {
                val normalized = current.path.replace('\\', '/')
                if (
                    normalized.contains("/src/main/") ||
                    normalized.contains("/src/test/") ||
                    normalized.endsWith("/src")
                ) {
                    val basePath = root.path.replace('\\', '/')
                    candidates += normalized.removePrefix(basePath).trimStart('/').ifBlank { current.name }
                }
                current.children.forEach { child -> queue.add(child to (depth + 1)) }
            }
        }

        return candidates.take(8)
    }
}
