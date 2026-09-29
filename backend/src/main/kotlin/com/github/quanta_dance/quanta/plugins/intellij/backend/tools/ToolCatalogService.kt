// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools

import com.github.quanta_dance.quanta.plugins.intellij.backend.services.BackendExecutionContextsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Supplies a stable, cache-first snapshot of built-in tools for a project.
 *
 * Agent turns must never synchronously inspect the project or runtime settings merely to decide which
 * tools to expose. A conservative startup snapshot is available immediately; capability detection and
 * configuration reconciliation happen on the isolated tool-catalog executor and atomically replace it.
 */
@Service(Service.Level.PROJECT)
class ToolCatalogService(
    private val project: Project,
) : Disposable {
    @Volatile
    private var toolsSnapshot: List<Class<out ToolInterface<out Any>>> = ToolsRegistry.defaultTools()

    private val refreshScheduled = AtomicBoolean(false)

    init {
        refreshAsync()
    }

    fun tools(): List<Class<out ToolInterface<out Any>>> = toolsSnapshot

    fun findTool(name: String): Class<out ToolInterface<out Any>>? =
        toolsSnapshot.firstOrNull { it.simpleName == name || it.name.endsWith(".$name") }

    /** Schedules one coalesced background refresh; callers never wait for project probing. */
    fun refreshAsync() {
        if (project.isDisposed || !refreshScheduled.compareAndSet(false, true)) return
        project.service<BackendExecutionContextsService>().toolCatalogScope.launch {
            try {
                toolsSnapshot = ToolsRegistry.detectTools(project)
            } finally {
                refreshScheduled.set(false)
            }
        }
    }

    override fun dispose() {
        refreshScheduled.set(false)
    }
}
