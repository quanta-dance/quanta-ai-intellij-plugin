// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Supplies cache-first repository context used by agent turns.
 *
 * Reading `AGENTS.md` and traversing the project tree are intentionally performed outside the agent-turn
 * path. Turns consume the latest completed snapshot immediately; a newly opened project may briefly use
 * an empty snapshot until the initial background refresh completes.
 */
@Service(Service.Level.PROJECT)
class ProjectContextSnapshotService(
    private val project: Project,
) : Disposable {
    @Volatile
    private var agentsMdSnapshot: String = ""

    @Volatile
    private var landscapeSnapshot: String = ""

    private val refreshScheduled = AtomicBoolean(false)

    init {
        refreshAsync()
    }

    fun agentsMd(): String = agentsMdSnapshot

    fun landscape(): String = landscapeSnapshot

    /** Schedules one coalesced refresh; consumers always receive the currently available snapshot. */
    fun refreshAsync() {
        if (project.isDisposed || !refreshScheduled.compareAndSet(false, true)) return
        project.service<BackendExecutionContextsService>().toolCatalogScope.launch {
            try {
                agentsMdSnapshot = ProjectAgentsFileManager(project).readAgentsFile(maxChars = MAX_AGENTS_MD_CHARS)
                landscapeSnapshot = ProjectLandscapeContextBuilder(project).buildMessage()
            } finally {
                refreshScheduled.set(false)
            }
        }
    }

    override fun dispose() {
        refreshScheduled.set(false)
    }

    companion object {
        private const val MAX_AGENTS_MD_CHARS = 8_000
    }
}
