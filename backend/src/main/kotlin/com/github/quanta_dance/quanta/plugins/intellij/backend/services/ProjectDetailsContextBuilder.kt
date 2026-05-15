// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.project.ProjectVersionUtil
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Builds a lightweight hidden project-details context message for OpenAI requests.
 *
 * This keeps project-summary formatting out of [OpenAIService] and colocates the small amount of
 * VFS/project-version probing needed for that message.
 */
class ProjectDetailsContextBuilder(
    private val project: Project,
) {
    fun buildSystemMessage(): String {
        val sdkVersion =
            try {
                ProjectVersionUtil.getProjectCompileVersion(project)
            } catch (_: Throwable) {
                null
            }
        val buildFiles =
            try {
                ProjectVersionUtil.getProjectBuildFiles(project)
            } catch (_: Throwable) {
                null
            }

        val filesCount =
            try {
                val basePath = PathUtils.projectRootPath(project)
                if (basePath != null) {
                    val root = LocalFileSystem.getInstance().findFileByPath(basePath)
                    if (root != null) {
                        var count = 0

                        fun dfs(v: VirtualFile) {
                            if (!v.isValid) return
                            if (v.isDirectory) {
                                v.children?.forEach { dfs(it) }
                            } else {
                                count++
                            }
                        }
                        dfs(root)
                        count
                    } else {
                        0
                    }
                } else {
                    0
                }
            } catch (_: Throwable) {
                0
            }

        return buildString {
            append("Project details (auto, hidden).\n")
            append("Available build files: ").append(buildFiles).append('\n')
            sdkVersion?.let { append(it).append('\n') }
            append("Files in the project: ").append(filesCount)
        }
    }
}
