// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.mcp

import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection

@Service(Service.Level.PROJECT)
class McpServersWatcher(private val project: Project) : Disposable {
    private val log = Logger.getInstance(McpServersWatcher::class.java)
    private val connection: MessageBusConnection

    // Absolute, system-independent path string for strict matching
    private val configPathSi: String? =
        McpServersPaths.resolve(project)
            ?.toAbsolutePath()
            ?.normalize()
            ?.let { FileUtilRt.toSystemIndependentName(it.toString()) }

    init {
        // Log what we are watching; notify only on error
        if (configPathSi.isNullOrEmpty()) {
            val msg =
                "MCP servers watcher could not resolve config path (project base: ${project.basePath ?: "<null>"}). " +
                    "No file changes will be observed."
            QDLog.warn(log) { msg }
        } else {
            // Do not show an info notification; log only
            QDLog.info(log) { "MCP servers watcher started. Watching: $configPathSi" }
        }

        connection = project.messageBus.connect(this)
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    val target = configPathSi
                    if (target.isNullOrEmpty()) return
                    val shouldReload =
                        events.any { e ->
                            val p = e.path
                            if (p.isEmpty()) return@any false
                            val si = FileUtilRt.toSystemIndependentName(p)
                            // Match only the exact config file path
                            si == target
                        }
                    if (shouldReload) {
                        QDLog.info(log) { "Detected change in ${McpServersPaths.RELATIVE_UNIX}, reloading MCP servers config…" }
                        // Trigger refresh on change only; no initial refresh here to avoid duplicates at startup
                        project.getService(McpClientService::class.java).refresh()
                    }
                }
            },
        )
        // No initial refresh here — McpClientService performs one in StartupActivity
    }

    override fun dispose() {
        // connection is disposed automatically with this Disposable parent
    }
}
