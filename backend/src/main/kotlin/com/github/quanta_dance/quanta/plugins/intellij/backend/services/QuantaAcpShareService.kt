// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationService
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.LocalQuantaAcpSessionDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaAcpShareDto
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hosts and joins short-lived, localhost-only ACP collaboration sessions.
 *
 * An invite carries a one-time token. Joining creates a normal [AcpAgentDto], so the paired IDE is
 * visible in the existing agentic roster and can be delegated work through the usual ACP boundary.
 */
@Service(Service.Level.PROJECT)
class QuantaAcpShareService(
    private val project: Project,
) : Disposable {
    private data class HostSession(
        val server: ServerSocket,
        val inviteToken: String?,
        val connectionToken: String,
        val expiresAtMillis: Long,
        val paired: AtomicBoolean = AtomicBoolean(inviteToken == null),
        @Volatile var peerAgentId: String? = null,
    )

    private data class CallbackEndpoint(
        val host: String,
        val port: Int,
        val token: String,
    )

    private data class PairingResponse(
        val connectionToken: String,
        val peerName: String,
        val peerIdentity: String?,
    )

    private val logger = Logger.getInstance(QuantaAcpShareService::class.java)
    private val mapper = ObjectMapper()
    private val executor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "qd-acp-share-${System.nanoTime()}").apply { isDaemon = true }
        }
    private val localPeerIdentity = UUID.randomUUID().toString()
    private val publisherProcessId = ProcessHandle.current().pid()
    private val localProjectPath =
        project.basePath?.let {
            Path
                .of(it)
                .toAbsolutePath()
                .normalize()
                .toString()
        }
    private val localSessionRegistry = LocalQuantaAcpSessionRegistry()
    private val joinedAgents = ConcurrentHashMap<String, AcpAgentDto>()
    private val hostedSessions = ConcurrentHashMap.newKeySet<HostSession>()
    private val reverseSessionsByAgentId = ConcurrentHashMap<String, HostSession>()
    private val activePeerTaskCounts = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    private var hostSession: HostSession? = null

    @Volatile
    private var connectedPeerName: String? = null

    init {
        createLocalShare(AUTO_DISCOVERY_TTL_MILLIS)
        project.service<ChatConversationService>().removeStaleQuantaAcpAgents(joinedAgents.keys)
    }

    @Synchronized
    fun createInvite(): QuantaAcpShareDto {
        stopSharing()
        return createLocalShare(INVITE_TTL_MILLIS)
    }

    @Synchronized
    private fun createLocalShare(ttlMillis: Long): QuantaAcpShareDto {
        val inviteToken = newToken()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val expiresAtMillis = System.currentTimeMillis() + ttlMillis
        val session =
            HostSession(
                server = server,
                inviteToken = inviteToken,
                connectionToken = newToken(),
                expiresAtMillis = expiresAtMillis,
            )
        hostedSessions += session
        hostSession = session
        executor.submit { acceptConnections(session) }
        val invite = "quanta-acp://join?host=127.0.0.1&port=${server.localPort}&token=$inviteToken&v=1"
        localSessionRegistry.publish(
            LocalQuantaAcpSessionDto(
                peerIdentity = localPeerIdentity,
                projectName = project.name,
                invite = invite,
                expiresAtMillis = expiresAtMillis,
                publisherProjectPath = localProjectPath,
                publisherProcessId = publisherProcessId,
            ),
        )
        return QuantaAcpShareDto(
            invite = invite,
            port = server.localPort,
            expiresAtMillis = expiresAtMillis,
        )
    }

    @Synchronized
    fun stopSharing() {
        hostedSessions.forEach { session -> session.server.close() }
        hostedSessions.clear()
        hostSession = null
        connectedPeerName = null
        localSessionRegistry.remove(localPeerIdentity)
    }

    fun availableLocalSessions(): List<LocalQuantaAcpSessionDto> =
        localSessionRegistry.available(
            excludingPeerIdentity = localPeerIdentity,
            excludingProjectPath = localProjectPath,
            excludingLegacyPublisherProcessId = publisherProcessId,
        )

    /** Removes a stale or unwanted local-session advertisement and any matching paired collaborator. */
    fun forgetLocalSession(peerIdentity: String) {
        if (peerIdentity == localPeerIdentity) return
        localSessionRegistry.remove(peerIdentity)
        joinedAgents.values
            .filter { agent -> agent.peerIdentity == peerIdentity }
            .forEach { agent -> removeJoinedAcpAgent(agent.id) }
    }

    @Synchronized
    fun join(invite: String): QuantaAcpShareDto {
        val parsed = parseInvite(invite) ?: return QuantaAcpShareDto(error = "Invalid Quanta ACP invite.")
        val reverseSession = createPeerSession()
        return runCatching {
            val (host, port, inviteToken) = parsed
            val pairing = pairWithHost(host, port, inviteToken, reverseSession)
            val endpoint = "tcp://$host:$port"
            val peerIdentity = pairing.peerIdentity ?: "$endpoint:${pairing.connectionToken}"
            val id = quantaPeerAgentId(peerIdentity)
            val agent =
                AcpAgentDto(
                    id = id,
                    name = "Shared Quanta · ${pairing.peerName}",
                    command = listOf("quanta-acp"),
                    executablePath = endpoint,
                    protocolVersion = ACP_PROTOCOL_VERSION,
                    peerIdentity = peerIdentity,
                    connectionToken = pairing.connectionToken,
                )
            replaceJoinedAgent(agent, reverseSession)
            project.service<ChatConversationService>().setAcpAgentAllowed(id, true)
            QuantaAcpShareDto(peerName = agent.name, connected = true)
        }.getOrElse { error ->
            reverseSession.server.close()
            QuantaAcpShareDto(error = error.message ?: "Could not pair with the Quanta ACP share.")
        }
    }

    fun joinedAcpAgents(): List<AcpAgentDto> = joinedAgents.values.sortedBy(AcpAgentDto::name)

    /** True while this paired Quanta peer has work executing in this IDE. */
    fun isJoinedPeerWorking(agentId: String): Boolean = activePeerTaskCounts[agentId]?.get()?.let { it > 0 } == true

    /** Removes a paired peer and revokes its permission for the active chat. */
    fun removeJoinedAcpAgent(agentId: String): Boolean {
        val removed = joinedAgents.remove(agentId)
        reverseSessionsByAgentId.remove(agentId)?.server?.close()
        project.service<ChatConversationService>().removeAcpAgentFromAllSessions(agentId)
        return removed != null
    }

    fun currentShare(): QuantaAcpShareDto =
        hostSession?.let { session ->
            QuantaAcpShareDto(
                port = session.server.localPort,
                expiresAtMillis = session.expiresAtMillis,
                peerName = connectedPeerName,
                connected = connectedPeerName != null,
            )
        } ?: QuantaAcpShareDto()

    private fun acceptConnections(session: HostSession) {
        while (!session.server.isClosed) {
            val socket = runCatching(session.server::accept).getOrNull() ?: break
            executor.submit { handleConnection(socket, session) }
        }
    }

    private fun handleConnection(
        socket: Socket,
        session: HostSession,
    ) {
        socket.use { connection ->
            val reader = connection.getInputStream().bufferedReader()
            val writer = connection.getOutputStream().bufferedWriter()
            var paired = false
            var peerName = "Shared Quanta session"
            var acpSessionId: String? = null
            while (true) {
                val request = reader.readLine() ?: return
                val message = runCatching { mapper.readTree(request) }.getOrNull() ?: continue
                val id = message.path("id")
                when (message.path("method").asText()) {
                    "quanta/ping" -> {
                        writeResult(writer, id, mapOf("peerIdentity" to localPeerIdentity))
                    }

                    "initialize" -> {
                        val quanta = message.path("params").path("clientCapabilities").path("quanta")
                        val token = quanta.path("token").asText()
                        val pairedWithInvite =
                            session.inviteToken == token &&
                                System.currentTimeMillis() < session.expiresAtMillis &&
                                session.paired.compareAndSet(false, true)
                        val pairedWithConnectionToken = token == session.connectionToken && session.paired.get()
                        if (!pairedWithInvite && !pairedWithConnectionToken) {
                            writeError(
                                writer,
                                id,
                                "The Quanta ACP pairing credential is invalid, expired, or already used.",
                            )
                            return
                        }
                        paired = true
                        peerName =
                            message
                                .path("params")
                                .path("clientInfo")
                                .path("name")
                                .asText("Shared Quanta")
                        val peerIdentity = quanta.path("peerIdentity").asText().ifBlank { null }
                        if (pairedWithInvite) {
                            connectedPeerName = peerName
                            callbackEndpoint(quanta)?.let { callback ->
                                registerPairedPeer(callback, peerName, peerIdentity, session)
                            }
                                ?: run {
                                    writeError(
                                        writer,
                                        id,
                                        "The Quanta ACP pairing request is missing its callback endpoint.",
                                    )
                                    return
                                }
                        }
                        writeResult(
                            writer,
                            id,
                            mapOf(
                                "protocolVersion" to ACP_PROTOCOL_VERSION,
                                "agentInfo" to mapOf("name" to project.name, "version" to "quanta"),
                                "quanta" to
                                    mapOf(
                                        "connectionToken" to session.connectionToken,
                                        "peerIdentity" to localPeerIdentity,
                                    ),
                            ),
                        )
                        if (pairedWithInvite) refreshAutomaticAdvertisement(session)
                    }

                    "session/new" -> {
                        if (!paired) {
                            writeError(writer, id, "Pair with initialize before creating an ACP session.")
                        } else {
                            acpSessionId = UUID.randomUUID().toString()
                            writeResult(writer, id, mapOf("sessionId" to acpSessionId))
                        }
                    }

                    "session/prompt" -> {
                        if (!paired) {
                            writeError(writer, id, "Pair with initialize before sending a prompt.")
                            continue
                        }
                        val text = message.path("params").path("prompt").firstText()
                        if (text.isBlank()) {
                            writeError(writer, id, "The shared ACP prompt is empty.")
                            continue
                        }
                        val peerAgentId = session.peerAgentId
                        peerAgentId?.let(::markPeerTaskStarted)
                        val responseText =
                            try {
                                runCatching {
                                    runBlocking {
                                        project.service<ChatConversationService>().processAcpPeerTask(peerName, text)
                                    }
                                }.getOrElse { error ->
                                    QDLog.warn(
                                        logger,
                                        { "Could not deliver shared ACP prompt: ${error.message}" },
                                        error,
                                    )
                                    "The shared Quanta ACP request could not be completed."
                                }
                            } finally {
                                peerAgentId?.let(::markPeerTaskFinished)
                            }
                        writeNotification(
                            writer,
                            "session/update",
                            mapOf(
                                "sessionId" to acpSessionId,
                                "update" to mapOf("content" to mapOf("type" to "text", "text" to responseText)),
                            ),
                        )
                        writeResult(writer, id, emptyMap<String, Any>())
                    }

                    else -> {
                        writeError(writer, id, "Unsupported Quanta ACP method.")
                    }
                }
            }
        }
    }

    private fun createPeerSession(): HostSession {
        val session =
            HostSession(
                server = ServerSocket(0, 1, InetAddress.getLoopbackAddress()),
                inviteToken = null,
                connectionToken = newToken(),
                expiresAtMillis = Long.MAX_VALUE,
            )
        executor.submit { acceptConnections(session) }
        return session
    }

    private fun pairWithHost(
        host: String,
        port: Int,
        inviteToken: String,
        reverseSession: HostSession,
    ): PairingResponse =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), PAIRING_TIMEOUT_MILLIS)
            socket.soTimeout = PAIRING_TIMEOUT_MILLIS
            val writer = socket.getOutputStream().bufferedWriter()
            val request =
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to PAIRING_REQUEST_ID,
                    "method" to "initialize",
                    "params" to
                        mapOf(
                            "protocolVersion" to ACP_PROTOCOL_VERSION,
                            "clientInfo" to mapOf("name" to project.name, "version" to "quanta"),
                            "clientCapabilities" to
                                mapOf(
                                    "quanta" to
                                        mapOf(
                                            "token" to inviteToken,
                                            "peerIdentity" to localPeerIdentity,
                                            "callback" to
                                                mapOf(
                                                    "host" to "127.0.0.1",
                                                    "port" to reverseSession.server.localPort,
                                                    "token" to reverseSession.connectionToken,
                                                ),
                                        ),
                                ),
                        ),
                )
            writer.write(mapper.writeValueAsString(request))
            writer.newLine()
            writer.flush()

            val response = mapper.readTree(socket.getInputStream().bufferedReader().readLine())
            response.path("error").takeIf { !it.isMissingNode && !it.isNull }?.let { error(responseError(it)) }
            val result = response.path("result")
            PairingResponse(
                connectionToken =
                    result
                        .path("quanta")
                        .path("connectionToken")
                        .asText()
                        .ifBlank { error("Missing connection token.") },
                peerName =
                    result
                        .path("agentInfo")
                        .path("name")
                        .asText()
                        .ifBlank { "Shared Quanta" },
                peerIdentity =
                    result
                        .path("quanta")
                        .path("peerIdentity")
                        .asText()
                        .ifBlank { null },
            )
        }

    private fun callbackEndpoint(quanta: JsonNode): CallbackEndpoint? =
        runCatching {
            val callback = quanta.path("callback")
            val host = callback.path("host").asText()
            require(host == "127.0.0.1" || host == "localhost" || host == "::1") { "Only localhost peers are supported." }
            CallbackEndpoint(
                host = host,
                port = callback.path("port").asInt().takeIf { it in 1..65_535 } ?: error("Invalid callback port."),
                token = callback.path("token").asText().ifBlank { error("Missing callback token.") },
            )
        }.getOrNull()

    private fun registerPairedPeer(
        callback: CallbackEndpoint,
        peerName: String,
        peerIdentity: String?,
        session: HostSession,
    ) {
        val endpoint = "tcp://${callback.host}:${callback.port}"
        val stablePeerIdentity = peerIdentity ?: "$endpoint:${callback.token}"
        val id = quantaPeerAgentId(stablePeerIdentity)
        val agent =
            AcpAgentDto(
                id = id,
                name = "Shared Quanta · $peerName",
                command = listOf("quanta-acp"),
                executablePath = endpoint,
                protocolVersion = ACP_PROTOCOL_VERSION,
                peerIdentity = stablePeerIdentity,
                connectionToken = callback.token,
            )
        replaceJoinedAgent(agent)
        session.peerAgentId = id
        project.service<ChatConversationService>().setAcpAgentAllowed(id, true)
    }

    /**
     * Replaces the consumed one-time discovery invite without closing the already paired listener.
     * Existing peers keep using the prior session's connection token; the registry always points at
     * the next one-time endpoint for a newly opened local IDE project.
     */
    @Synchronized
    private fun refreshAutomaticAdvertisement(consumedSession: HostSession) {
        if (hostSession !== consumedSession || consumedSession.server.isClosed) return
        createLocalShare(AUTO_DISCOVERY_TTL_MILLIS)
    }

    private fun quantaPeerAgentId(peerIdentity: String): String = "quanta:$peerIdentity"

    private fun replaceJoinedAgent(
        agent: AcpAgentDto,
        reverseSession: HostSession? = null,
    ) {
        val replacedAgentIds =
            joinedAgents.entries
                .filter { (_, existing) ->
                    existing.peerIdentity == agent.peerIdentity ||
                        (existing.peerIdentity == null && existing.name == agent.name)
                }.map { it.key }
        replacedAgentIds.forEach { replacedId ->
            joinedAgents.remove(replacedId)
            reverseSessionsByAgentId.remove(replacedId)?.server?.close()
            project.service<ChatConversationService>().removeAcpAgentFromAllSessions(replacedId)
        }
        joinedAgents[agent.id] = agent
        reverseSession?.let {
            it.peerAgentId = agent.id
            reverseSessionsByAgentId[agent.id] = it
        }
    }

    private fun markPeerTaskStarted(agentId: String) {
        activePeerTaskCounts.computeIfAbsent(agentId) { AtomicInteger() }.incrementAndGet()
    }

    private fun markPeerTaskFinished(agentId: String) {
        activePeerTaskCounts.computeIfPresent(agentId) { _, count ->
            if (count.decrementAndGet() <= 0) null else count
        }
    }

    private fun responseError(error: JsonNode): String = error.path("message").asText("Unknown Quanta ACP pairing error.")

    private fun newToken(): String =
        ByteArray(TOKEN_BYTES)
            .also(SecureRandom()::nextBytes)
            .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    private fun writeNotification(
        writer: java.io.BufferedWriter,
        method: String,
        params: Map<String, Any?>,
    ) = write(writer, mapOf("jsonrpc" to "2.0", "method" to method, "params" to params))

    private fun writeResult(
        writer: java.io.BufferedWriter,
        id: JsonNode,
        result: Any,
    ) = write(writer, mapOf("jsonrpc" to "2.0", "id" to id.asText(), "result" to result))

    private fun writeError(
        writer: java.io.BufferedWriter,
        id: JsonNode,
        message: String,
    ) = write(
        writer,
        mapOf(
            "jsonrpc" to "2.0",
            "id" to id.asText(),
            "error" to mapOf("code" to -32001, "message" to message),
        ),
    )

    private fun write(
        writer: java.io.BufferedWriter,
        message: Map<String, Any?>,
    ) {
        writer.write(mapper.writeValueAsString(message))
        writer.newLine()
        writer.flush()
    }

    private fun JsonNode.firstText(): String =
        path("content").path("text").asText().ifBlank {
            get(0)?.path("text")?.asText().orEmpty()
        }

    private fun parseInvite(invite: String): Triple<String, Int, String>? =
        runCatching {
            val uri = java.net.URI(invite.trim())
            require(uri.scheme == "quanta-acp" && uri.host == "join") { "Not a Quanta ACP invite." }
            val parameters =
                uri.rawQuery.orEmpty().split("&").associate {
                    val (key, value) = it.split("=", limit = 2)
                    key to java.net.URLDecoder.decode(value, Charsets.UTF_8)
                }
            val host = parameters["host"] ?: error("Missing host")
            require(host == "127.0.0.1" || host == "localhost" || host == "::1") { "Only localhost invites are supported." }
            Triple(
                host,
                parameters["port"]?.toInt()?.takeIf { it in 1..65_535 } ?: error("Invalid port"),
                parameters["token"] ?: error("Missing token"),
            )
        }.getOrNull()

    override fun dispose() {
        stopSharing()
        reverseSessionsByAgentId.values.forEach { session -> session.server.close() }
        reverseSessionsByAgentId.clear()
        executor.shutdownNow()
        joinedAgents.clear()
        activePeerTaskCounts.clear()
    }

    private companion object {
        const val ACP_PROTOCOL_VERSION = 1
        const val INVITE_TTL_MILLIS = 10 * 60 * 1_000L
        const val AUTO_DISCOVERY_TTL_MILLIS = 24 * 60 * 60 * 1_000L
        const val TOKEN_BYTES = 32
        const val PAIRING_TIMEOUT_MILLIS = 5_000
        const val PAIRING_REQUEST_ID = 1
    }
}
