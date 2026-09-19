// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp

import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.BackendExecutionContextsService
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.micrometer.context.ContextRegistry
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapperSupplier
import io.modelcontextprotocol.json.schema.jackson2.JacksonJsonSchemaValidatorSupplier
import io.modelcontextprotocol.spec.McpClientTransport
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.Tool
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import reactor.core.publisher.Hooks
import reactor.util.context.ReactorContextAccessor
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal fun requiresInteractiveMcpConnection(config: McpServerConfig?): Boolean = config?.url != null

/** HTTP is permitted only for a literal loopback MCP endpoint; remote MCP servers must use HTTPS. */
internal fun isLoopbackMcpUrl(url: String?): Boolean =
    runCatching {
        val uri = URI(url)
        uri.scheme.equals("http", ignoreCase = true) &&
            uri.host?.removeSurrounding("[", "]")?.lowercase() in setOf("localhost", "127.0.0.1", "::1")
    }.getOrDefault(false)

private fun requireSupportedMcpUrl(url: String): URI =
    URI(url).also { uri ->
        val scheme = uri.scheme?.lowercase()
        require(!uri.host.isNullOrBlank()) { "MCP server URL must be absolute" }
        require(scheme == "https" || isLoopbackMcpUrl(url)) {
            "MCP server URL must use HTTPS, except for localhost, 127.0.0.1, or ::1 loopback endpoints"
        }
    }

internal fun oauthRefreshDelayMillis(
    expiresAtSeconds: Long,
    nowSeconds: Long,
    leewaySeconds: Long,
): Long = ((expiresAtSeconds - nowSeconds - leewaySeconds).coerceAtLeast(0L)) * 1_000

internal fun shouldRetryOAuthRefresh(
    attempts: Int,
    expiresAtSeconds: Long,
    nowSeconds: Long,
    maxAttempts: Int,
    retrySeconds: Long,
): Boolean = attempts < maxAttempts && expiresAtSeconds - nowSeconds > retrySeconds

/** Produces concise recoverable MCP connection copy while retaining full failures in the IDE log. */
internal fun mcpConnectionErrorMessage(error: Throwable): String =
    when {
        generateSequence(error) { it.cause }.any { it is java.util.concurrent.TimeoutException || it is TimeoutCancellationException } -> {
            "MCP initialization did not finish within 30 seconds. Check the server configuration, then select Retry."
        }

        else -> {
            error.message ?: error.javaClass.simpleName
        }
    }

/**
 * Reactor reports exceptions emitted after a subscriber has already been cancelled through its global
 * `onErrorDropped` hook. The MCP SDK's stdio transport can legitimately produce either of these
 * specific pipe-write errors while it is shutting down its outbound writer after a child process or
 * client has closed the pipe.
 *
 * It is not an MCP request failure; the owning client operation reports that outcome separately. Do
 * not suppress other dropped errors: they still need a visible backend log for diagnosis.
 */
internal fun isExpectedStdioTransportShutdownError(error: Throwable): Boolean =
    generateSequence(error) { it.cause }
        .any { cause ->
            cause is IOException && cause.message?.trim()?.lowercase() in setOf("stream closed", "broken pipe")
        }

private val reactorDroppedErrorHookInstalled = AtomicBoolean(false)

@Service(Service.Level.PROJECT)
class McpClientService(
    private val project: Project,
) : Disposable {
    private val log = Logger.getInstance(McpClientService::class.java)
    private val executionContexts = project.getService(BackendExecutionContextsService::class.java)
    private val oauth =
        McpOAuthService { serverName ->
            notifyRuntimeConfigIssue(
                title = "Authorize MCP server",
                content =
                    "Opening your default browser to authorize MCP server '$serverName'. " +
                        "Complete sign-in there, then return to IntelliJ.",
                type = NotificationType.INFORMATION,
            )
        }

    data class ServerStatus(
        val connected: Boolean,
        val connecting: Boolean,
        val toolCount: Int,
        val error: String? = null,
    )

    @Volatile
    private var serversConfig: McpServersFile = McpServersFile()
    private val clients = ConcurrentHashMap<String, McpSyncClient>()
    private val toolCache = ConcurrentHashMap<String, List<Tool>>()
    private val serverErrors = ConcurrentHashMap<String, String>()
    private val connectingServers = ConcurrentHashMap.newKeySet<String>()
    private val authorizationRequestedServers = ConcurrentHashMap.newKeySet<String>()
    private val authorizationRequiredServers = ConcurrentHashMap.newKeySet<String>()
    private val oauthChallenges = ConcurrentHashMap<String, McpOAuthService.AuthorizationChallenge>()
    private val oauthRefreshJobs = ConcurrentHashMap<String, Job>()
    private val oauthRefreshFailures = ConcurrentHashMap<String, Int>()

    // A failed remote endpoint must not repeatedly trigger OAuth or connection attempts during tool polling.
    // It is cleared only by a configuration change/removal or a successful connection.
    private val failedUrlConnections = ConcurrentHashMap<String, String>()
    private val initialized = AtomicBoolean(false)
    private val refreshScheduled = AtomicBoolean(false)

    @Volatile
    private var lastLoadedConfigHash: Int? = null

    @Volatile
    private var configLoadError: String? = null

    init {
        installReactorDroppedErrorHandler()
        // Do not schedule refresh here; perform initial refresh from ProjectActivity when project is open
        QDLog.debug(log) { "McpClientService init: awaiting startup activity for initial refresh" }
    }

    /**
     * The Reactor hook is process-wide because Reactor does not offer a per-transport dropped-error
     * handler. Install it exactly once and narrowly suppress only the expected stdio pipe-close race.
     */
    private fun installReactorDroppedErrorHandler() {
        if (!reactorDroppedErrorHookInstalled.compareAndSet(false, true)) return
        Hooks.onErrorDropped { error ->
            if (isExpectedStdioTransportShutdownError(error)) {
                QDLog.debug(log) { "MCP stdio outbound writer stopped after its pipe closed" }
            } else {
                QDLog.error(log, { "Unexpected dropped Reactor error in MCP runtime" }, error)
            }
        }
    }

    override fun dispose() {
        QDLog.debug(log) { "McpClientService dispose: shutting down ${clients.size} MCP servers" }
        oauthRefreshJobs.values.forEach(Job::cancel)
        oauthRefreshJobs.clear()
        (clients.keys + toolCache.keys).toSet().forEach { name ->
            shutdownServer(name)
        }
        clients.clear()
        toolCache.clear()
    }

    private fun notifyRuntimeConfigIssue(
        title: String,
        content: String,
        type: NotificationType,
    ) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Plugin Notifications")
        if (group == null) {
            QDLog.info(log) { "$title: $content" }
            return
        }
        group.createNotification(title, content, type).notify(project)
    }

    /**
     * Queues configuration reconciliation on the MCP lifecycle executor and returns immediately.
     *
     * Connection setup, OAuth, server shutdown, and tool discovery must never block settings sync,
     * chat publication, or agent orchestration. A single queued refresh coalesces rapid settings edits;
     * if settings change while it runs, one additional pass reconciles the latest snapshot.
     */
    fun refresh() {
        if (!refreshScheduled.compareAndSet(false, true)) {
            QDLog.debug(log) { "McpClientService refresh: reload already queued or running" }
            return
        }
        executionContexts.mcpLifecycleScope.launch {
            try {
                refreshNow()
            } finally {
                refreshScheduled.set(false)
                val currentHash =
                    BackendRuntimeSettingsService.instance.settings.mcpServersJson
                        .hashCode()
                if (currentHash != lastLoadedConfigHash) {
                    QDLog.debug(log) { "McpClientService refresh: settings changed during reload; queuing another pass" }
                    refresh()
                }
            }
        }
    }

    private fun refreshNow() {
        val firstRun = initialized.compareAndSet(false, true)
        if (firstRun) {
            QDLog.info(log) { "McpClientService refresh: loading config and starting servers" }
        } else {
            QDLog.info(log) { "McpClientService refresh: reloading config and reconciling servers" }
        }

        val mcpConfigJson = BackendRuntimeSettingsService.instance.settings.mcpServersJson
        lastLoadedConfigHash = mcpConfigJson.hashCode()
        QDLog.info(log) { "McpClientService refresh: using synced frontend MCP config, chars=${mcpConfigJson.length}" }
        val load =
            McpServersConfigLoader.loadJsonWithDiagnostics(
                mcpConfigJson,
                sourceName = "frontend-synced mcp-servers.json",
            )
        if (load.parseError != null) {
            configLoadError = load.parseError
            notifyRuntimeConfigIssue(
                "Invalid synced MCP configuration",
                load.parseError,
                NotificationType.ERROR,
            )
            QDLog.warn(log) { "refresh(): parse error - ${load.parseError}" }
            return
        }
        configLoadError = null
        val newConfig = load.file ?: McpServersFile()
        QDLog.info(log) { "McpClientService refresh: loaded ${newConfig.mcpServers.size} configured server(s)" }
        if (newConfig.mcpServers.isEmpty()) {
            QDLog.warn(log) { "McpClientService refresh: zero MCP servers configured after load; check file content and resolved path" }
        }
        if (load.validationWarnings.isNotEmpty()) {
            val msg = load.validationWarnings.joinToString("\n")
            notifyRuntimeConfigIssue(
                "Synced MCP configuration warnings",
                msg,
                NotificationType.WARNING,
            )
            QDLog.warn(log) { "mcp-servers.json warnings: \n$msg" }
        }

        QDLog.debug(log) { "Loaded mcpServers: ${newConfig.mcpServers.keys.joinToString()}" }

        // Reconcile current running state with new config
        try {
            reconcileConfigs(serversConfig, newConfig)
            serversConfig = newConfig
        } catch (t: Throwable) {
            logRuntimeDependencyFailure("refresh reconcile", t)
            throw t
        }

        // Probe every configured transport with the configured headers. Browser OAuth is requested only
        // after that probe receives an actual HTTP 401 response, never by guessing header names.
        serversConfig.mcpServers.forEach { (name, config) ->
            QDLog.info(log) { "McpClientService refresh: scheduling tool discovery for configured server '$name'" }
            discoverToolsAsync(name)
        }
    }

    private fun logRuntimeDependencyFailure(
        operation: String,
        error: Throwable,
    ) {
        val details =
            generateSequence(error as Throwable?) { it.cause }
                .take(8)
                .joinToString(" <- ") { current ->
                    val type = current::class.qualifiedName ?: current.javaClass.name
                    val message = current.message ?: "<no message>"
                    "$type: $message"
                }
        val missingDependency =
            generateSequence(error as Throwable?) { it.cause }
                .mapNotNull { current ->
                    when (current) {
                        is NoClassDefFoundError -> current.message
                        is ClassNotFoundException -> current.message
                        else -> null
                    }
                }.firstOrNull()
        if (missingDependency != null) {
            QDLog.error(
                log,
                { "McpClientService $operation failed due to missing runtime dependency: $missingDependency. Cause chain: $details" },
                error,
            )
        } else {
            QDLog.error(log, { "McpClientService $operation failed. Cause chain: $details" }, error)
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
        oauthRefreshJobs.remove(name)?.cancel()
        oauthChallenges.remove(name)
        oauthRefreshFailures.remove(name)
        clients.remove(name)?.let { client ->
            runCatching { client.close() }
        }
        toolCache.remove(name)
        serverErrors.remove(name)
        failedUrlConnections.remove(name)
        authorizationRequestedServers.remove(name)
        authorizationRequiredServers.remove(name)
    }

    private fun startServer(
        name: String,
        cfg: McpServerConfig,
    ) {
        if (cfg.url != null) {
            connectAndDiscoverAsync(name, cfg)
            return
        }
        if (clients.containsKey(name)) {
            QDLog.debug(log) { "startServer: server '$name' already has a client" }
            return
        }
        QDLog.debug(log) { "startServer: preparing stdio server '$name'" }
        ensureClient(name, cfg)
    }

    /**
     * Registers Reactor's context adapter without relying on ServiceLoader's thread-context loader.
     *
     * IntelliJ's split backend class loader can hide service-provider resources from Micrometer even
     * though the provider class is bundled in this module. Reactor 3.7 consults this registry while
     * initializing its HTTP transport, so register the adapter explicitly before building a client.
     */
    private fun ensureReactorContextAccessor() {
        val registry = ContextRegistry.getInstance()
        synchronized(registry) {
            if (registry.contextAccessors.none { it is ReactorContextAccessor }) {
                registry.registerContextAccessor(ReactorContextAccessor())
                QDLog.debug(log) { "Registered Reactor context accessor for MCP runtime" }
            }
        }
    }

    private fun createClient(transport: McpClientTransport): McpSyncClient {
        ensureReactorContextAccessor()
        return McpClient
            .sync(transport)
            // .clientInfo(Implementation("Quanta-AI-IDE", "1.0"))
            .initializationTimeout(Duration.ofSeconds(30))
            .requestTimeout(Duration.ofSeconds(120))
            // IntelliJ's plugin class loader does not make Java ServiceLoader providers
            // reliably visible to the SDK. Configure the bundled Jackson 2 validator directly.
            .jsonSchemaValidator(JacksonJsonSchemaValidatorSupplier().get())
            .loggingConsumer { message -> System.out.println("Log message: " + message) }
            .build()
            .also { it.initialize() }
    }

    private fun ensureClient(
        name: String,
        cfg: McpServerConfig,
    ): McpSyncClient? {
        clients[name]?.let { return it }
        val command = cfg.command ?: return null
        return try {
            QDLog.debug(log) { "ensureClient: creating stdio transport for '$name'" }
            val serverParameters =
                ServerParameters
                    .builder(command)
                    .args(cfg.args)
                    .env(cfg.env ?: emptyMap())
                    .build()
            val transport = StdioClientTransport(serverParameters, JacksonMcpJsonMapperSupplier().get())
            transport.setStdErrorHandler { line -> QDLog.warn(log) { "[$name][stderr] $line" } }
            QDLog.debug(log) { "ensureClient: connecting client for '$name'" }
            val client = createClient(transport)
            QDLog.info(log) { "ensureClient: connected to MCP server '$name'" }
            clients[name] = client
            serverErrors.remove(name)
            client
        } catch (e: Exception) {
            serverErrors[name] = mcpConnectionErrorMessage(e)
            QDLog.error(log, { "ensureClient failed for '$name'" }, e)
            null
        }
    }

    /**
     * Returns cached MCP tools immediately for remote servers and refreshes the cache in the background.
     *
     * A URL server can require interactive OAuth. Waiting for its browser callback here would make callers
     * such as response construction block for up to the OAuth timeout when the user declines to open the
     * browser. Once remote discovery has completed, including with an empty tool list, reuse that result
     * until configuration reconciliation invalidates it. Re-listing tools for every model turn needlessly
     * adds remote work and repeatedly rebuilds identical OpenAI tool definitions.
     * Local stdio servers retain the synchronous behavior because no user interaction is needed.
     */
    fun getTools(server: String): List<Tool> {
        refreshIfConfigChanged()
        val configuredServer = serversConfig.mcpServers[server]
        if (requiresInteractiveMcpConnection(configuredServer)) {
            toolCache[server]?.let { cached ->
                QDLog.debug(log) { "getTools[$server]: using cached remote tool list (${cached.size} tool(s))" }
                return cached
            }
            discoverToolsAsync(server)
            return emptyList()
        }
        toolCache[server]?.let { cached ->
            if (cached.isNotEmpty()) return cached
        }
        return try {
            discoverTools(server)
        } catch (e: Exception) {
            QDLog.warn(log) { "getTools($server): discovery failed - ${e.message}" }
            toolCache[server] ?: emptyList()
        }
    }

    private fun causeDetails(error: Throwable): String =
        generateSequence(error as Throwable?) { it.cause }
            .take(8)
            .joinToString(" <- ") { cause ->
                val type = cause::class.qualifiedName ?: cause.javaClass.name
                "$type: ${cause.message ?: "<no message>"}"
            }

    private fun buildUrlTransport(
        url: String,
        scheme: String?,
        requestBuilder: HttpRequest.Builder,
    ): McpClientTransport {
        val endpointUri = requireSupportedMcpUrl(url)
        require(endpointUri.scheme.equals(scheme, ignoreCase = true)) { "MCP server URL scheme changed unexpectedly" }
        val baseUri = "${endpointUri.scheme}://${endpointUri.rawAuthority}"
        val endpoint = endpointUri.rawPath.ifBlank { "/" } + endpointUri.rawQuery?.let { "?$it" }.orEmpty()
        return HttpClientStreamableHttpTransport
            .builder(baseUri)
            .endpoint(endpoint)
            .clientBuilder(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)))
            .requestBuilder(requestBuilder)
            .jsonMapper(JacksonMcpJsonMapperSupplier().get())
            .build()
    }

    private fun ensureClientUrl(
        name: String,
        cfg: McpServerConfig,
    ): McpSyncClient? {
        clients[name]?.let { return it }
        failedUrlConnections[name]?.let { failure ->
            QDLog.debug(log) { "ensureClient(url): '$name' is paused after its previous connection failure: $failure" }
            return null
        }
        val url = cfg.url ?: return null
        return try {
            val endpointUri = requireSupportedMcpUrl(url)
            val scheme = endpointUri.scheme.lowercase()
            QDLog.info(log) {
                "ensureClient(url): effective config for '$name': transport=streamable-http, " +
                    "oauth=${if (scheme == "https") "probe-on-401" else "disabled-for-loopback-http"}"
            }

            try {
                QDLog.info(log) { "ensureClient(url): connecting '$name' to $url via Streamable HTTP" }
                val requestBuilder = HttpRequest.newBuilder()
                var oauthTokenAttached = false
                cfg.headers?.forEach { (header, value) -> requestBuilder.header(header, value) }
                // Always test the configured headers first. An HTTP 401—not an inferred credential-header
                // name—is the only condition that exposes the roster's Authorize action.
                if (scheme == "https") {
                    val probe = oauth.probeAuthorization(url, cfg.headers)
                    if (probe.statusCode == 401) {
                        authorizationRequiredServers.add(name)
                        val challenge =
                            probe.challenge
                                ?: error(
                                    "MCP server '$name' rejected the configured headers with HTTP 401 but did not " +
                                        "advertise an OAuth authorization challenge",
                                )
                        oauthChallenges[name] = challenge
                        if (name !in authorizationRequestedServers) {
                            serverErrors[name] =
                                "Authorization required. Use the MCP tools button to authorize this server."
                            QDLog.info(log) { "ensureClient(url): initial probe for '$name' was unauthorized" }
                            return null
                        }
                        val accessToken = oauth.accessToken(name, url, challenge, cfg.oauthClientId)
                        QDLog.info(log) {
                            "ensureClient(url): adding OAuth bearer token for '$name' " +
                                "(accessTokenLength=${accessToken.length}, " +
                                "accessTokenSha256Prefix=${oauthTokenFingerprint(accessToken)})"
                        }
                        requestBuilder.header("Authorization", "Bearer $accessToken")
                        oauthTokenAttached = true
                    }
                }
                val transport = buildUrlTransport(url, scheme, requestBuilder)
                val client =
                    runBlocking(executionContexts.mcpDispatcher) {
                        withTimeout(30_000) { createClient(transport) }
                    }
                QDLog.info(log) { "ensureClient(url): connected to MCP server '$name' via Streamable HTTP" }
                clients[name] = client
                serverErrors.remove(name)
                failedUrlConnections.remove(name)
                authorizationRequiredServers.remove(name)
                authorizationRequestedServers.remove(name)
                oauthRefreshFailures.remove(name)
                if (oauthTokenAttached) {
                    oauth.storedTokenExpiresAt(name, url)?.let { expiresAt ->
                        scheduleOAuthRefresh(name, cfg, expiresAt)
                    }
                }
                return client
            } catch (_: TimeoutCancellationException) {
                serverErrors[name] = "timed out connecting via Streamable HTTP"
                QDLog.warn(log) {
                    "ensureClient(url): MCP initialization timed out for configured server '$name' at $url " +
                        "(transport=streamable-http)"
                }
            } catch (e: Exception) {
                val failure = e.message ?: e.javaClass.simpleName
                serverErrors[name] = failure
                QDLog.warn(log) {
                    "ensureClient(url): MCP initialization failed for configured server '$name' at $url " +
                        "(transport=streamable-http): $failure; cause chain: ${causeDetails(e)}"
                }
            }

            val failure = serverErrors[name] ?: "remote MCP connection failed"
            failedUrlConnections[name] = failure
            notifyRuntimeConfigIssue(
                title = "MCP server unavailable",
                content = "Could not connect to MCP server '$name': $failure. Use Retry in MCP tools to try again.",
                type = NotificationType.WARNING,
            )
            QDLog.warn(log) {
                "ensureClient(url): pausing automatic retries for configured server '$name' at $url " +
                    "after connection failure: $failure. Use explicit Retry or update its configuration."
            }
            null
        } catch (e: Exception) {
            val failure = e.message ?: e.javaClass.simpleName
            serverErrors[name] = failure
            failedUrlConnections[name] = failure
            QDLog.warn(log) {
                "ensureClient(url): pausing automatic retries for '$name' after setup failure: $failure. " +
                    "Change its MCP configuration or restart the IDE before retrying."
            }
            QDLog.error(log, { "ensureClient(url) failed for '$name'" }, e)
            null
        }
    }

    /** Schedules a silent OAuth refresh before expiry; only a terminal failure changes the visible server state. */
    private fun scheduleOAuthRefresh(
        name: String,
        cfg: McpServerConfig,
        expiresAt: Long,
    ) {
        val delayMillis =
            oauthRefreshDelayMillis(
                expiresAtSeconds = expiresAt,
                nowSeconds =
                    java.time.Instant
                        .now()
                        .epochSecond,
                leewaySeconds = OAUTH_REFRESH_LEEWAY_SECONDS,
            )
        scheduleOAuthRefreshAttempt(name, cfg, delayMillis)
    }

    private fun scheduleOAuthRefreshAttempt(
        name: String,
        cfg: McpServerConfig,
        delayMillis: Long,
    ) {
        val job =
            executionContexts.mcpLifecycleScope.launch {
                delay(delayMillis)
                refreshOAuthToken(name, cfg)
            }
        oauthRefreshJobs.put(name, job)?.cancel()
        QDLog.debug(log) { "MCP OAuth: scheduled token refresh for '$name' in ${delayMillis}ms" }
    }

    private fun refreshOAuthToken(
        name: String,
        cfg: McpServerConfig,
    ) {
        val url = cfg.url ?: return
        val challenge =
            oauthChallenges[name]
                ?: oauth.probeAuthorization(url, cfg.headers).challenge
                ?: run {
                    markOAuthRefreshFailure(name, url, "The MCP server no longer advertises an OAuth challenge")
                    return
                }
        oauthChallenges[name] = challenge
        when (val refresh = oauth.refreshStoredToken(name, url, challenge, cfg.oauthClientId)) {
            is McpOAuthService.TokenRefreshResult.Success -> {
                QDLog.info(log) { "MCP OAuth: token refresh succeeded for '$name'; reconnecting with the refreshed token" }
                oauthRefreshFailures.remove(name)
                clients.remove(name)?.let { client -> runCatching { client.close() } }
                toolCache.remove(name)
                serverErrors.remove(name)
                failedUrlConnections.remove(name)
                authorizationRequestedServers.add(name)
                connectAndDiscoverAsync(name, cfg)
            }

            is McpOAuthService.TokenRefreshResult.Failure -> {
                val expiresAt = oauth.storedTokenExpiresAt(name, url) ?: 0L
                val attempts = oauthRefreshFailures.merge(name, 1, Int::plus) ?: 1
                if (
                    shouldRetryOAuthRefresh(
                        attempts = attempts,
                        expiresAtSeconds = expiresAt,
                        nowSeconds =
                            java.time.Instant
                                .now()
                                .epochSecond,
                        maxAttempts = MAX_OAUTH_REFRESH_ATTEMPTS,
                        retrySeconds = OAUTH_REFRESH_RETRY_SECONDS,
                    )
                ) {
                    QDLog.warn(log) {
                        "MCP OAuth: refresh attempt $attempts for '$name' failed before expiry; retrying in " +
                            "$OAUTH_REFRESH_RETRY_SECONDS seconds: ${refresh.message}"
                    }
                    scheduleOAuthRefreshAttempt(name, cfg, OAUTH_REFRESH_RETRY_SECONDS * 1_000)
                } else {
                    markOAuthRefreshFailure(name, url, refresh.message)
                }
            }
        }
    }

    private fun markOAuthRefreshFailure(
        name: String,
        url: String,
        reason: String,
    ) {
        oauthRefreshJobs.remove(name)?.cancel()
        clients.remove(name)?.let { client -> runCatching { client.close() } }
        toolCache.remove(name)
        oauth.clearStoredToken(name, url)
        authorizationRequestedServers.remove(name)
        authorizationRequiredServers.add(name)
        failedUrlConnections.remove(name)
        serverErrors[name] = "OAuth session could not be refreshed. Select Authorize to sign in again."
        notifyRuntimeConfigIssue(
            title = "MCP authorization expired",
            content = "Authorization for MCP server '$name' could not be refreshed. Use the MCP tools button to authorize it again.",
            type = NotificationType.WARNING,
        )
        QDLog.warn(log) { "MCP OAuth: refresh failed permanently for '$name': $reason" }
    }

    private fun discoverTools(server: String): List<Tool> {
        val startedAtNanos = System.nanoTime()
        QDLog.info(log) { "discoverTools[$server]: starting discovery" }
        val client =
            clients[server] ?: run {
                val cfg = serversConfig.mcpServers[server]
                if (cfg?.url != null) {
                    ensureClientUrl(
                        server,
                        cfg,
                    )
                } else {
                    cfg?.let { ensureClient(server, it) }
                }
            } ?: return emptyList()
        val tools =
            runBlocking(executionContexts.mcpDispatcher) {
                withTimeout(30_000) { client.listTools().tools() }
            }
        toolCache[server] = tools
        val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
        QDLog.info(log) {
            "discoverTools[$server]: discovered ${tools.size} tool(s) in ${elapsedMs}ms: " +
                tools.joinToString { it.name() }
        }
        return tools
    }

    private fun discoverToolsAsync(server: String) {
        val cfg = serversConfig.mcpServers[server] ?: return
        connectAndDiscoverAsync(server, cfg)
    }

    private fun connectAndDiscoverAsync(
        server: String,
        cfg: McpServerConfig,
    ) {
        if (!connectingServers.add(server)) {
            QDLog.debug(log) { "MCP connection/discovery for '$server' is already in progress" }
            return
        }
        executionContexts.mcpLifecycleScope.launch {
            try {
                if (cfg.url != null) ensureClientUrl(server, cfg) else ensureClient(server, cfg)
                clients[server]?.let { discoverTools(server) }
            } catch (e: Exception) {
                QDLog.warn(log) { "connectAndDiscoverAsync[$server]: failed - ${causeDetails(e)}" }
            } finally {
                connectingServers.remove(server)
            }
        }
    }

    fun listServers(): List<String> {
        refreshIfConfigChanged()
        return serversConfig.mcpServers.keys.sorted()
    }

    fun getServerStatus(name: String): ServerStatus {
        val connected = clients.containsKey(name)
        val connecting = !connected && name in connectingServers
        val toolCount = toolCache[name]?.size ?: 0
        val error = serverErrors[name]
        return ServerStatus(connected, connecting, toolCount, error)
    }

    /** Whether the last configured-header probe received HTTP 401 for this server. */
    fun requiresAuthorization(name: String): Boolean = name in authorizationRequiredServers && !clients.containsKey(name)

    /** Explicit user-requested reconnection. OAuth starts only when the server previously returned HTTP 401. */
    fun retryConnection(name: String): Boolean {
        val config = serversConfig.mcpServers[name] ?: return false
        if (name in connectingServers) return true
        clients.remove(name)?.let { client -> runCatching { client.close() } }
        toolCache.remove(name)
        serverErrors.remove(name)
        failedUrlConnections.remove(name)
        if (name in authorizationRequiredServers) {
            authorizationRequestedServers.add(name)
        } else {
            authorizationRequestedServers.remove(name)
        }
        connectAndDiscoverAsync(name, config)
        return true
    }

    fun getConfigLoadError(): String? = configLoadError

    /** True while the latest frontend-synced configuration has not been reconciled yet. */
    fun isConfigurationLoading(): Boolean = !initialized.get() || refreshScheduled.get()

    fun getConfiguredCount(): Int = serversConfig.mcpServers.size

    private companion object {
        const val OAUTH_REFRESH_LEEWAY_SECONDS = 60L
        const val OAUTH_REFRESH_RETRY_SECONDS = 30L
        const val MAX_OAUTH_REFRESH_ATTEMPTS = 3
    }

    private fun extractFirstNumber(text: String): Number? {
        val m = Regex("[-+]?\\d+(?:\\.\\d+)?").find(text)
        return m?.value?.let { it.toLongOrNull() ?: it.toDoubleOrNull() }
    }

    private fun refreshIfConfigChanged() {
        val currentHash =
            BackendRuntimeSettingsService.instance.settings.mcpServersJson
                .hashCode()
        if (!initialized.get() || currentHash != lastLoadedConfigHash) {
            QDLog.info(log) { "McpClientService: synced MCP config changed or not initialized, refreshing before serving MCP data" }
            refresh()
        }
    }

    private fun coerceArgsHeuristics(args: MutableMap<String, Any?>) {
        val numericKeys =
            setOf("project_id", "merge_request_iid", "iid", "id", "limit", "offset", "page", "per_page")
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
        val tool = toolCache[server]?.firstOrNull { it.name() == toolName }
        val props =
            try {
                tool?.inputSchema()?.get("properties") as? Map<String, Any?> ?: emptyMap()
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
                    "number", "integer" -> {
                        if (v is String) {
                            val num = extractFirstNumber(v)
                            if (num != null) {
                                args[key] =
                                    if (expectedType == "integer") num.toLong() else num.toDouble()
                            }
                        }
                    }

                    "boolean" -> {
                        if (v is String) {
                            val b =
                                when (v.trim().lowercase()) {
                                    "true", "1", "yes", "on" -> true
                                    "false", "0", "no", "off" -> false
                                    else -> null
                                }
                            if (b != null) args[key] = b
                        }
                    }

                    "string" -> {
                        if (v is Number || v is Boolean) args[key] = v.toString()
                    }
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
        refreshIfConfigChanged()
        if (!serversConfig.mcpServers.containsKey(server)) return "MCP server '$server' not found in claude_desktop_config.json"
        val client =
            clients[server] ?: run {
                val cfg = serversConfig.mcpServers[server]
                if (cfg?.url != null) {
                    ensureClientUrl(server, cfg)
                } else {
                    cfg?.let { ensureClient(server, it) }
                }
            } ?: return "MCP client for '$server' is not available"

        val args = input.toMutableMap()

        toolCache[server]?.firstOrNull { it.name() == toolName }?.let { tool ->
            try {
                val schema = tool.inputSchema()
                val required = schema["required"] as? List<String> ?: emptyList()
                val props = schema["properties"] as? Map<String, Any?>
                val missing = required.filter { req -> !args.containsKey(req) || args[req] == null }
                if (missing.isNotEmpty()) {
                    val propsSummary =
                        if (props.isNullOrEmpty()) {
                            "<unknown>"
                        } else {
                            props.entries.joinToString(", ") { (k, v) ->
                                val type =
                                    try {
                                        v?.let { it::class.java.getMethod("getType").invoke(it) as? String }
                                    } catch (_: Throwable) {
                                        null
                                    }
                                if (type != null) "$k:$type" else k
                            }
                        }
                    val msg =
                        "Missing required parameter(s): ${missing.joinToString(", ")}. Known properties: $propsSummary"
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
            val result =
                runBlocking(executionContexts.mcpDispatcher) {
                    withTimeout(timeoutMs) { client.callTool(CallToolRequest(toolName, args)) }
                }
            val duration = System.currentTimeMillis() - started
            QDLog.debug(log) { "invokeTool[$server.$toolName]: completed in ${duration}ms" }
            val text =
                result
                    .content()
                    .mapNotNull { content ->
                        when (content) {
                            is TextContent -> content.text()
                            else -> null
                        }
                    }.joinToString("\n")
                    .trim()
            text.ifEmpty { "MCP call $server.$toolName returned no textual content" }
        } catch (_: TimeoutCancellationException) {
            "MCP call timed out after ${timeoutMs}ms"
        } catch (e: Exception) {
            "MCP call failed: ${e.javaClass.simpleName}: ${e.message ?: "no message"}"
        }
    }
}
