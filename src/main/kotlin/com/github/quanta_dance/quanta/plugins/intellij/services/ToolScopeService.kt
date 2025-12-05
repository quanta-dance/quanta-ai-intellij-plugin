// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class ToolScopeService(private val project: Project) {
    private val log = Logger.getInstance(ToolScopeService::class.java)

    // Sticky scope persists across turns until changed
    private val stickyBuiltIns = ConcurrentHashMap.newKeySet<String>()
    private val stickyMcpMethods = ConcurrentHashMap.newKeySet<String>() // format: server.method

    // Current turn scope (cleared after consumption)
    @Volatile private var currentBuiltIns: Set<String> = emptySet()
    @Volatile private var currentMcpMethods: Set<String> = emptySet()

    fun setScope(
        builtIns: Collection<String>?,
        mcpMethods: Collection<String>?,
        mcpServersAllMethods: Collection<String>?,
        sticky: Boolean,
        mcpResolver: (String) -> Collection<String>, // server -> [server.method]
    ): Map<String, Any> {
        val acceptedBuiltIns = (builtIns ?: emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val mcpFromMethods = (mcpMethods ?: emptyList()).map { it.trim() }.filter { it.contains('.') }.toSet()
        val fromServers = (mcpServersAllMethods ?: emptyList()).flatMap { server -> mcpResolver(server) }.toSet()
        val acceptedMcp = (mcpFromMethods + fromServers).toSet()

        if (sticky) {
            stickyBuiltIns.addAll(acceptedBuiltIns)
            stickyMcpMethods.addAll(acceptedMcp)
        } else {
            currentBuiltIns = acceptedBuiltIns
            currentMcpMethods = acceptedMcp
        }
        return mapOf(
            "acceptedBuiltIns" to acceptedBuiltIns.toList(),
            "acceptedMcp" to acceptedMcp.toList(),
            "stickyApplied" to sticky,
        )
    }

    fun consumeCurrent(): Pair<Set<String>, Set<String>> {
        val b = currentBuiltIns
        val m = currentMcpMethods
        currentBuiltIns = emptySet()
        currentMcpMethods = emptySet()
        return b to m
    }

    fun getSticky(): Pair<Set<String>, Set<String>> = stickyBuiltIns.toSet() to stickyMcpMethods.toSet()

    fun clearSticky() {
        stickyBuiltIns.clear(); stickyMcpMethods.clear()
    }
}
