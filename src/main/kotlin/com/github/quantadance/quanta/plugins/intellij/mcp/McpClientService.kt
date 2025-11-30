// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.mcp

import com.github.quantadance.quanta.plugins.intellij.services.QDLog
import com.github.quantadance.quanta.plugins.intellij.services.ToolWindowService
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.WebSocketClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

@Service(Service.Level.PROJECT)
class McpClientService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(McpClientService::class.java)

    @Volatile
    private var serversConfig: McpServersFile = McpServersFile()
    private val processes = ConcurrentHashMap<String, Process>()
    private val clients = ConcurrentHashMap<String, Client>()
    private val toolCache = ConcurrentHashMap<String, List<Tool>>()
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val initialized = AtomicBoolean(false)

    init {
        // Do not schedule refresh here; perform initial refresh from ProjectActivity when project is open
        QDLog.debug(log) { "McpClientService init: awaiting startup activity for initial refresh" }
    }

    override fun dispose() {
        QDLog.debug(log) { "McpClientService dispose: destroying ${processes.size} processes" }
        processes.values.forEach { p ->
            try {
                p.destroyForcibly()
            } catch (_: Throwable) {
            }
        }
        executor.shutdownNow()
    }

    private fun notifyWithOpenAction(
        title: String,
        content: String,
        type: NotificationType,
    ) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Plugin Notifications")
        val notification = group.createNotification(title, content, type)
        val base = project.basePath
        if (base != null) {
            val file = File(base, ".quantadance/mcp-servers.json")
            notification.addAction(
                NotificationAction.create("Open config") { _, n ->
                    val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                    if (vFile != null) {
                        FileEditorManager.getInstance(project).openFile(vFile, true)
                    }
                    n.expire()
                },
            )
        }
        notification.notify(project)
    }

    fun refresh() {
        val firstRun = initialized.compareAndSet(false, true)
        if (firstRun) {
            QDLog.info(log) { "McpClientService refresh: loading config and starting servers" }
        } else {
            QDLog.info(log) { "McpClientService refresh: reloading config and reconciling servers" }
        }

        val load = McpServersConfigLoader.loadWithDiagnostics(project)
        if (load.parseError != null) {
            // Keep existing config running; inform the user with clickable link
            notifyWithOpenAction(
                "Invalid .quantadance/mcp-servers.json",
                load.parseError,
                NotificationType.ERROR,
            )
            QDLog.warn(log) { "refresh(): parse error - ${load.parseError}" }
            return
        }
        val newConfig = load.file ?: McpServersFile()
        if (load.validationWarnings.isNotEmpty()) {
            val msg = load.validationWarnings.joinToString("\n")
            notifyWithOpenAction(
                "mcp-servers.json warnings",
                msg,
                NotificationType.WARNING,
            )
            QDLog.warn(log) { "mcp-servers.json warnings: \n$msg" }
        }

        QDLog.debug(log) { "Loaded mcpServers: ${newConfig.mcpServers.keys.joinToString()}" }

        // Reconcile current running state with new config
        reconcileConfigs(serversConfig, newConfig)
        serversConfig = newConfig

        // Schedule tool discovery for all active servers
        serversConfig.mcpServers.keys.forEach { name ->
            QDLog.debug(log) { "Scheduling tool discovery for server '$name'" }
            discoverToolsAsync(name)
        }
    }

    private fun reconcileConfigs(
        oldCfg: McpServersFile,
        newCfg: McpServersFile,
    ) {
        val oldServers = oldCfg.mcpServers
        val newServers = newCfg.mcpServers

        val removed = oldServers.keys - newServers.keys
        val added = newServers.keys - oldServers.keys
        val maybeChanged = newServers.keys.intersect(oldServers.keys)

        // Stop removed servers and clear their tools
        removed.forEach { name -> shutdownServer(name) }

        // Start added servers
        added.forEach { name -> startServer(name, newServers.getValue(name)) }

        // Restart changed servers
        maybeChanged.forEach { name ->
            val old = oldServers[name]
            val neu = newServers[name]
            if (old != neu && neu != null) {
                shutdownServer(name)
                startServer(name, neu)
            }
        }

        QDLog.info(log) {
            "Reconcile complete. added=${added.size}, removed=${removed.size}, " +
                "changed=${maybeChanged.count { oldServers[it] != newServers[it] }}"
        }
    }

    private fun shutdownServer(name: String) {
        QDLog.info(log) { "Shutting down MCP server '$name'" }
        // Stop process if any
        processes.remove(name)?.let { p ->
            try {
                p.destroyForcibly()
            } catch (_: Throwable) {
            }
        }
        // Close client if any
        clients.remove(name)?.let { c ->
            try {
                runCatching { runBlocking { c.close() } }
            } catch (_: Throwable) {
            }
        }
        // Clear discovered tools
        toolCache.remove(name)
    }

    private fun startServer(
        name: String,
        cfg: McpServerConfig,
    ) {
        if (cfg.url != null) {
            ensureClientUrl(name, cfg)
            return
        }
        if (processes.containsKey(name)) {
            QDLog.debug(log) { "startServer: server '$name' already has a process" }
            processes[name]?.let { ensureClient(name, it) }
            return
        }
        QDLog.debug(log) { "startServer: preparing '$name' (transport=${cfg.transport})" }
        if ((cfg.transport ?: "stdio").lowercase() != "stdio") {
            QDLog.warn(log) { "MCP server '$name' uses unsupported transport '${cfg.transport}'. Skipping." }
            return
        }
        try {
            val cmd = mutableListOf(cfg.command ?: return).apply { addAll(cfg.args) }
            val pb = ProcessBuilder(cmd)
            project.basePath?.let { base -> pb.directory(java.io.File(base)) }
            cfg.env?.let { env -> pb.environment().putAll(env) }
            QDLog.info(log) { "Starting MCP server '$name' with command: ${cmd.joinToString(" ")}" }
            val proc = pb.start()
            processes[name] = proc
            executor.submit {
                try {
                    BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).use { r ->
                        var line = r.readLine()
                        while (line != null) {
                            QDLog.warn(log) { "[$name][stderr] $line" }
                            line = r.readLine()
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            ensureClient(name, proc)
        } catch (e: Exception) {
            QDLog.error(log, { "Failed to start MCP server '$name'" }, e)
        }
    }

    private fun ensureClient(
        name: String,
        proc: Process,
    ): Client? {
        clients[name]?.let { return it }
        return try {
            QDLog.debug(log) { "ensureClient: creating transport for '$name'" }
            val source = proc.inputStream.asSource().buffered()
            val sink = proc.outputStream.asSink().buffered()
            val transport = StdioClientTransport(source, sink)
            val client = Client(Implementation("Quanta-AI-IDE", "1.0"), ClientOptions())
            QDLog.debug(log) { "ensureClient: connecting client for '$name'" }
            runBlocking { client.connect(transport) }
            QDLog.info(log) { "ensureClient: connected to MCP server '$name'" }
            clients[name] = client
            client
        } catch (e: Exception) {
            QDLog.error(log, { "ensureClient failed for '$name'" }, e)
            null
        }
    }

    fun getTools(server: String): List<Tool> = toolCache[server] ?: emptyList()

    private fun ensureClientUrl(
        name: String,
        cfg: McpServerConfig,
    ): Client? {
        clients[name]?.let { return it }
        val url = cfg.url ?: return null
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase()
            val pref = cfg.transport?.lowercase()
            val httpClient =
                HttpClient(CIO) {
                    install(SSE) { reconnectionTime = 1.seconds }
                    install(WebSockets)
                    install(HttpTimeout) {
                        connectTimeoutMillis = 30_000
                        requestTimeoutMillis = 5 * 60_000L
                        socketTimeoutMillis = 5 * 60_000L
                    }
                    defaultRequest {
                        cfg.headers?.forEach { (k, v) -> headers.append(k, v) }
                        cfg.headers?.get(HttpHeaders.Authorization)?.let { headers.append(HttpHeaders.Authorization, it) }
                    }
                }

            QDLog.debug(log) { "ensureClient(url): connecting '$name' to $url via scheme=$scheme, pref=$pref" }

            val client = Client(Implementation("Quanta-AI-IDE", "1.0"), ClientOptions())

            val transport: AbstractTransport =
                when {
                    pref == "websocket" -> {
                        require(scheme == "ws" || scheme == "wss") { "transport=websocket requires ws:// or wss:// URL" }
                        WebSocketClientTransport(httpClient, url)
                    }

                    pref == "sse" -> {
                        require(scheme == "http" || scheme == "https") { "transport=sse requires http:// or https:// URL" }
                        StreamableHttpClientTransport(httpClient, url = url)
                    }

                    scheme == "ws" || scheme == "wss" -> WebSocketClientTransport(httpClient, url)
                    scheme == "http" || scheme == "https" -> StreamableHttpClientTransport(httpClient, url = url)
                    else -> error("Unsupported URL scheme: $scheme")
                }

            runBlocking { client.connect(transport) }

            QDLog.info(log) { "ensureClient(url): connected to MCP server '$name'" }
            clients[name] = client
            client
        } catch (e: Exception) {
            QDLog.error(log, { "ensureClient(url) failed for '$name'" }, e)
            null
        }
    }

    private fun discoverToolsAsync(server: String) {
        executor.submit {
            try {
                val client =
                    clients[server] ?: run {
                        val cfg = serversConfig.mcpServers[server]
                        if (cfg?.url != null) {
                            ensureClientUrl(
                                server,
                                cfg,
                            )
                        } else {
                            processes[server]?.let { ensureClient(server, it) }
                        }
                    } ?: return@submit
                val res = runBlocking { client.listTools(ListToolsRequest()) }
                val tools = res.tools
                toolCache[server] = tools
                QDLog.info(log) { "discoverToolsAsync[$server]: discovered ${tools.size} tool(s): ${tools.joinToString { it.name }}" }
            } catch (e: Exception) {
                QDLog.warn(log) { "discoverToolsAsync[$server]: failed - ${e.message}" }
            }
        }
    }

    fun listServers(): List<String> = serversConfig.mcpServers.keys.sorted()

    private fun extractFirstNumber(text: String): Number? {
        val m = Regex("[-+]?\\d+(?:\\.\\d+)?").find(text)
        return m?.value?.let { it.toLongOrNull() ?: it.toDoubleOrNull() }
    }

    private fun coerceArgsHeuristics(args: MutableMap<String, Any?>) {
        val numericKeys = setOf("project_id", "merge_request_iid", "iid", "id", "limit", "offset", "page", "per_page")
        val regexNumeric = Regex(".*(_id|_iid|_number|_count)$")
        args.keys.toList().forEach { key ->
            val v = args[key]
            if (v is String) {
                val trimmed = v.trim()
                val num: Number? = extractFirstNumber(trimmed)
                if (num != null && (key in numericKeys || regexNumeric.matches(key))) {
                    args[key] = num
                }
                val lowered = trimmed.lowercase()
                if (lowered == "true" || lowered == "false") {
                    args[key] = lowered == "true"
                }
            }
        }
    }

    private fun coerceArgsToSchema(
        server: String,
        toolName: String,
        args: MutableMap<String, Any?>,
    ) {
        val before = args.toMap()
        val tool = toolCache[server]?.firstOrNull { it.name == toolName }
        val props =
            try {
                tool?.inputSchema?.properties ?: emptyMap<String, Any?>()
            } catch (_: Throwable) {
                emptyMap()
            }
        if (props.isEmpty()) {
            coerceArgsHeuristics(args)
            QDLog.debug(log) { "coerceArgsHeuristics applied for $server.$toolName: before=$before after=$args" }
            return
        }
        props.forEach { (key, def) ->
            val expectedType =
                try {
                    def?.let { def::class.java.getMethod("getType").invoke(def) as? String }
                } catch (_: Throwable) {
                    null
                }?.lowercase()
            val v = args[key]
            if (v == null || expectedType == null) return@forEach
            try {
                when (expectedType) {
                    "number", "integer" ->
                        if (v is String) {
                            val num = extractFirstNumber(v)
                            if (num != null) args[key] = if (expectedType == "integer") num.toLong() else num.toDouble()
                        }

                    "boolean" ->
                        if (v is String) {
                            val b =
                                when (v.trim().lowercase()) {
                                    "true", "1", "yes", "on" -> true
                                    "false", "0", "no", "off" -> false
                                    else -> null
                                }
                            if (b != null) args[key] = b
                        }

                    "string" -> if (v is Number || v is Boolean) args[key] = v.toString()
                }
            } catch (_: Throwable) {
            }
        }
        QDLog.debug(log) { "coerceArgsToSchema for $server.$toolName: before=$before after=$args" }
    }

    fun invokeTool(
        server: String,
        toolName: String,
        input: Map<String, Any?>,
        timeoutSec: Int? = null,
    ): String {
        if (!serversConfig.mcpServers.containsKey(server)) return "MCP server '$server' not found in claude_desktop_config.json"
        val client =
            clients[server] ?: run {
                val cfg = serversConfig.mcpServers[server]
                if (cfg?.url != null) ensureClientUrl(server, cfg) else processes[server]?.let { ensureClient(server, it) }
            } ?: return "MCP client for '$server' is not available"

        val args = input.toMutableMap()

        toolCache[server]?.firstOrNull { it.name == toolName }?.let { tool ->
            try {
                val required = tool.inputSchema.required ?: emptyList()
                val props = tool.inputSchema.properties
                val missing = required.filter { req -> !args.containsKey(req) || args[req] == null }
                if (missing.isNotEmpty()) {
                    val propsSummary =
                        if (props.isEmpty()) {
                            "<unknown>"
                        } else {
                            props.entries.joinToString(", ") { (k, v) ->
                                val type =
                                    try {
                                        v.let { v::class.java.getMethod("getType").invoke(v) as? String }
                                    } catch (_: Throwable) {
                                        null
                                    }
                                if (type != null) "$k:$type" else k
                            }
                        }
                    val msg =
                        "Missing required parameter(s): ${missing.joinToString(", ")}. Known properties: $propsSummary"
                    project.service<ToolWindowService>().addToolingMessage("MCP $server.$toolName", msg)
                    return msg
                }
            } catch (_: Throwable) {
            }
        }

        // Coerce argument types
        coerceArgsToSchema(server, toolName, args)
        // Drop null-valued keys to avoid sending nulls to servers that expect missing/omitted optionals
        args.entries.removeIf { it.value == null }

        val timeoutMs = ((timeoutSec ?: 120).toLong()) * 1000
        return try {
            val started = System.currentTimeMillis()
            val result = runBlocking { withTimeout(timeoutMs) { client.callTool(toolName, args, false) } }
            val duration = System.currentTimeMillis() - started
            val contents = result?.content ?: emptyList()
            val text =
                contents.mapNotNull {
                    try {
                        val cls = it::class.java
                        val getter = cls.methods.firstOrNull { m -> m.name == "getText" && m.parameterCount == 0 }
                        getter?.invoke(it) as? String
                    } catch (_: Throwable) {
                        null
                    }
                }.joinToString("\n").trim()
            project.service<ToolWindowService>().addToolingMessage(
                "MCP $server.$toolName",
                "Completed in ${duration}ms. Args: ${
                    if (args.isEmpty()) {
                        "<no args>"
                    } else {
                        args.entries.joinToString(", ") { (k, v) -> "$k=$v" }
                    }
                }",
            )
            text.ifEmpty { "MCP call $server.$toolName returned no textual content" }
        } catch (_: TimeoutCancellationException) {
            project.service<ToolWindowService>()
                .addToolingMessage("MCP $server.$toolName", "Timed out after ${timeoutMs}ms")
            "MCP call timed out after ${timeoutMs}ms"
        } catch (e: Exception) {
            project.service<ToolWindowService>().addToolingMessage(
                "MCP $server.$toolName",
                "Failed: ${e.javaClass.simpleName}: ${e.message ?: "no message"}",
            )
            "MCP call failed: ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
        }
    }
}
