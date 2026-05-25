// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

private val resolverLogger = Logger.getInstance("BackendRpcProjectResolver")
private val fallbackPathsLogged = ConcurrentHashMap.newKeySet<String>()

internal fun findBackendProject(projectPath: String): Project? {
    val openProjects = ProjectManager.getInstance().openProjects
    val normalizedInput = normalizeProjectPath(projectPath)

    if (normalizedInput != null) {
        openProjects
            .firstOrNull { project ->
                normalizeProjectPath(project.basePath) == normalizedInput
            }?.let { return it }
    }

    if (openProjects.size == 1) {
        val fallback = openProjects.first()
        val trimmedPath = projectPath.trim()
        if (fallbackPathsLogged.add(trimmedPath)) {
            resolverLogger.info(
                "Backend RPC project resolver falling back to the only open project '${fallback.name}' for path='$trimmedPath'",
            )
        }
        return fallback
    }

    resolverLogger.warn(
        "Backend RPC project resolver could not resolve path='${projectPath.trim()}' among open projects=${
            openProjects.map {
                it.basePath ?: it.name
            }
        }",
    )
    return null
}

private fun normalizeProjectPath(path: String?): String? {
    val trimmed = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        Paths
            .get(trimmed)
            .toAbsolutePath()
            .normalize()
            .toString()
    }.getOrElse { trimmed }
}
