package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory scope state for tool availability.
 *
 * This service lets a tool request a reduced tool set for the current turn or persist a
 * "sticky" scope across later turns. It was designed to support agent steering and safety
 * controls, but it is currently disabled because no public tool entrypoint registers it.
 */
@Service(Service.Level.PROJECT)
class ToolScopeService(private val project: Project) {
    private val log = Logger.getInstance(ToolScopeService::class.java)

    // Sticky scope persists across turns until changed.
    private val stickyBuiltIns = ConcurrentHashMap.newKeySet<String>()
    private val stickyMcpMethods = ConcurrentHashMap.newKeySet<String>() // format: server.method

    // Current-turn scope is consumed once and then cleared.
    @Volatile
    private var currentBuiltIns: Set<String> = emptySet()

    @Volatile
    private var currentMcpMethods: Set<String> = emptySet()

    /**
     * Updates the active tool scope.
     *
     * @param builtIns built-in tool simple names to allow
     * @param mcpMethods explicit MCP method ids in `server.method` form
     * @param mcpServersAllMethods MCP servers whose full method lists should be enabled
     * @param sticky when true, changes persist across later turns
     * @param mcpResolver resolves a server name to its available `server.method` ids
     * @return a small result map describing what was accepted
     */
    fun setScope(
        builtIns: Collection<String>?,
        mcpMethods: Collection<String>?,
        mcpServersAllMethods: Collection<String>?,
        sticky: Boolean,
        mcpResolver: (String) -> Collection<String>,
        // server -> [server.method]
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

    /** Returns and clears the current-turn scope. */
    fun consumeCurrent(): Pair<Set<String>, Set<String>> {
        val b = currentBuiltIns
        val m = currentMcpMethods
        currentBuiltIns = emptySet()
        currentMcpMethods = emptySet()
        return b to m
    }

    /** Returns the sticky scope that persists across turns. */
    fun getSticky(): Pair<Set<String>, Set<String>> = stickyBuiltIns.toSet() to stickyMcpMethods.toSet()

    /** Clears all sticky scope state. */
    fun clearSticky() {
        stickyBuiltIns.clear()
        stickyMcpMethods.clear()
    }
}