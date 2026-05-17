// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.settings

import com.github.quanta_dance.quanta.plugins.intellij.frontend.logging.FrontendBackendLogBridge
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Single frontend-side owner for MCP config path, file IO, and file-change sync behavior.
 *
 * The user edits this file on the frontend machine. This service ensures every frontend code path
 * (edit/read/watch/sync) resolves the same durable file and pushes changes to the backend runtime.
 */
@Service(Service.Level.PROJECT)
class FrontendMcpConfigService(
    private val project: Project,
) : AutoCloseable {
    private val log = project.service<FrontendBackendLogBridge>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connection: MessageBusConnection = project.messageBus.connect()

    @Volatile
    private var syncJob: Job? = null

    private val configFile: File by lazy {
        File(
            stableConfigRoot(),
            ".quantadance/mcp-servers.json",
        )
    }

    private val configPathSi: String by lazy {
        FileUtilRt.toSystemIndependentName(configFile.absoluteFile.normalize().path)
    }

    init {
        log.info("Frontend MCP config service started for path=$configPathSi project=${project.name}")
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    val changed =
                        events.any { event ->
                            val path = event.path
                            path.isNotBlank() && FileUtilRt.toSystemIndependentName(path) == configPathSi
                        }
                    if (!changed) return

                    log.info("Detected frontend MCP config change at $configPathSi, scheduling backend sync")
                    syncJob?.cancel()
                    syncJob =
                        scope.launch {
                            delay(250)
                            val text = readForSync()
                            if (text == null) {
                                log.warn("Skipping MCP sync because file content is empty or unreadable during save")
                                return@launch
                            }
                            project.service<FrontendSettingsSyncStateService>().retryNow()
                        }
                }
            },
        )
    }

    fun file(): File = configFile

    fun ensureExists(): File {
        val file = configFile
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.writeText(DEFAULT_MCP_JSON)
            log.info("Created default frontend MCP config at ${file.absolutePath}; watcher is active for path=$configPathSi")
            syncJob?.cancel()
            syncJob =
                scope.launch {
                    project.service<FrontendSettingsSyncStateService>().retryNow()
                }
        }
        return file
    }

    fun readForSync(): String? =
        try {
            val file = configFile
            if (!file.exists()) {
                log.info("Frontend MCP config is absent at ${file.absolutePath}; returning empty JSON for sync")
                DEFAULT_MCP_JSON
            } else {
                val text = file.readText()
                if (text.isBlank()) {
                    log.warn("Frontend MCP config read blank content from ${file.absolutePath}; skipping sync for this transient state")
                    null
                } else {
                    log.info("Frontend MCP config read from ${file.absolutePath}, chars=${text.length}")
                    text
                }
            }
        } catch (t: Throwable) {
            log.warn("Failed to read frontend MCP config for sync: ${t.message}")
            null
        }

    override fun close() {
        syncJob?.cancel()
        connection.dispose()
        scope.cancel()
    }

    private fun stableConfigRoot(): File =
        PathManager
            .getCommonDataPath()
            .resolve("QuantaDance")
            .toFile()

    private companion object {
        private const val DEFAULT_MCP_JSON: String = "{\n  \"mcpServers\": { }\n}\n"
    }
}
