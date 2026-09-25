// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationService
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
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
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    )

    private data class CallbackEndpoint(
        val host: String,
        val port: Int,
        val token: String,
    )

    private data class PairingResponse(
        val connectionToken: String,
        val peerName: String,
    )

    private val logger = Logger.getInstance(QuantaAcpShareService::class.java)
    private val mapper = ObjectMapper()
    private val executor: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "qd-acp-share-${System.nanoTime()}").apply { isDaemon = true }
        }
    private val joinedAgents = ConcurrentHashMap<String, AcpAgentDto>()
    private val reverseSessionsByAgentId = ConcurrentHashMap<String, HostSession>()

    @Volatile
    private var hostSession: HostSession? = null

    @Volatile
    private var connectedPeerName: String? = null

    @Synchronized
    fun createInvite(): QuantaAcpShareDto {
        stopSharing()
        val inviteToken = newToken()
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val expiresAtMillis = System.currentTimeMillis() + INVITE_TTL_MILLIS
        val session =
            HostSession(
                server = server,
                inviteToken = inviteToken,
                connectionToken = newToken(),
                expiresAtMillis = expiresAtMillis,
            )
        hostSession = session
        executor.submit { acceptConnections(session) }
        return QuantaAcpShareDto(
            invite = "quanta-acp://join?host=127.0.0.1&port=${server.localPort}&token=$inviteToken&v=1",
            port = server.localPort,
            expiresAtMillis = expiresAtMillis,
        )
    }

    @Synchronized
    fun stopSharing() {
        hostSession?.server?.close()
        hostSession = null
        connectedPeerName = null
    }

    @Synchronized
    fun join(invite: String): QuantaAcpShareDto {
        val parsed = parseInvite(invite) ?: return QuantaAcpShareDto(error = "Invalid Quanta ACP invite.")
        val reverseSession = createPeerSession()
        return runCatching {
            val (host, port, inviteToken) = parsed
            val pairing = pairWithHost(host, port, inviteToken, reverseSession)
            val endpoint = "tcp://$host:$port"
            val id = UUID.nameUUIDFromBytes("$endpoint:${pairing.connectionToken}".toByteArray()).toString()
            val agent =
                AcpAgentDto(
                    id = id,
                    name = "Shared Quanta · ${pairing.peerName}",
                    command = listOf("quanta-acp"),
                    executablePath = endpoint,
                    protocolVersion = ACP_PROTOCOL_VERSION,
                    connectionToken = pairing.connectionToken,
                )
            joinedAgents[id] = agent
            reverseSessionsByAgentId[id] = reverseSession
            project.service<ChatConversationService>().setAcpAgentAllowed(id, true)
            QuantaAcpShareDto(peerName = agent.name, connected = true)
        }.getOrElse { error ->
            reverseSession.server.close()
            QuantaAcpShareDto(error = error.message ?: "Could not pair with the Quanta ACP share.")
        }
    }

    fun joinedAcpAgents(): List<AcpAgentDto> = joinedAgents.values.sortedBy(AcpAgentDto::name)

    /** Removes a paired peer and revokes its permission for the active chat. */
    fun removeJoinedAcpAgent(agentId: String): Boolean {
        val removed = joinedAgents.remove(agentId) ?: return false
        reverseSessionsByAgentId.remove(agentId)?.server?.close()
        project.service<ChatConversationService>().setAcpAgentAllowed(removed.id, false)
        return true
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
                        if (pairedWithInvite) {
                            connectedPeerName = peerName
                            callbackEndpoint(quanta)?.let { callback -> registerPairedPeer(callback, peerName) }
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
                                "quanta" to mapOf("connectionToken" to session.connectionToken),
                            ),
                        )
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
                        val responseText =
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
    ) {
        val endpoint = "tcp://${callback.host}:${callback.port}"
        val id = UUID.nameUUIDFromBytes("$endpoint:${callback.token}".toByteArray()).toString()
        joinedAgents[id] =
            AcpAgentDto(
                id = id,
                name = "Shared Quanta · $peerName",
                command = listOf("quanta-acp"),
                executablePath = endpoint,
                protocolVersion = ACP_PROTOCOL_VERSION,
                connectionToken = callback.token,
            )
        project.service<ChatConversationService>().setAcpAgentAllowed(id, true)
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
        mapOf("jsonrpc" to "2.0", "id" to id.asText(), "error" to mapOf("code" to -32001, "message" to message)),
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
    }

    private companion object {
        const val ACP_PROTOCOL_VERSION = 1
        const val INVITE_TTL_MILLIS = 10 * 60 * 1_000L
        const val TOKEN_BYTES = 32
        const val PAIRING_TIMEOUT_MILLIS = 5_000
        const val PAIRING_REQUEST_ID = 1
    }
}
