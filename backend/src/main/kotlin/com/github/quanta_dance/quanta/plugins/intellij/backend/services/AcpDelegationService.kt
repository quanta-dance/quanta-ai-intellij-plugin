// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Opens and manages ACP sessions for external agents.
 *
 * Discovery probes remain short-lived in [AcpAgentDiscoveryService]. A [LiveSession] owns the
 * separate, persistent transport used for collaboration: the main agent can send additional
 * prompts on the same ACP session until it is cancelled or explicitly closed.
 */
class AcpDelegationService(
    private val processFactory: (List<String>, Map<String, String>) -> Process = ::startProcess,
    private val socketFactory: () -> Socket = ::Socket,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    fun openSession(
        agent: AcpAgentDto,
        workspacePath: String?,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        cancellation: CancellationSignal = CancellationSignal(),
        onInteraction: (AcpInteraction) -> Unit = {},
        onTextUpdate: (String) -> Unit = {},
    ): LiveSession {
        val transport = openTransport(agent, timeoutMillis)
        cancellation.register(transport)
        return try {
            cancellation.throwIfCancelled()
            QDLog.debug(logger) { "ACP collaboration session opening for ${agent.name} (${agent.id})" }
            transport.request(INITIALIZE_METHOD, initializeParams(), INITIALIZE_REQUEST_ID, timeoutMillis)
            cancellation.throwIfCancelled()
            val session =
                transport.request(
                    SESSION_NEW_METHOD,
                    sessionNewParams(workspacePath),
                    SESSION_NEW_REQUEST_ID,
                    timeoutMillis,
                )
            val sessionId =
                session.path("sessionId").asText().takeIf(String::isNotBlank)
                    ?: error("ACP agent did not return a session ID.")
            LiveSession(agent, sessionId, transport, cancellation, timeoutMillis, onInteraction, onTextUpdate)
        } catch (error: Throwable) {
            cancellation.clear(transport)
            transport.close()
            throw error
        }
    }

    /** Compatibility wrapper for callers that intentionally need one prompt and no retained session. */
    fun delegate(
        agent: AcpAgentDto,
        task: String,
        workspacePath: String?,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        cancellation: CancellationSignal = CancellationSignal(),
        onInteraction: (AcpInteraction) -> Unit = {},
        onTextUpdate: (String) -> Unit = {},
    ): AcpDelegationResult =
        runCatching {
            openSession(agent, workspacePath, timeoutMillis, cancellation, onInteraction, onTextUpdate).use { session ->
                session.prompt(task)
            }
        }.getOrElse { error -> failure(agent, error.message ?: error::class.simpleName.orEmpty()) }

    inner class LiveSession internal constructor(
        val agent: AcpAgentDto,
        val sessionId: String,
        private val transport: AcpTransport,
        private val cancellation: CancellationSignal,
        private val timeoutMillis: Long,
        private val onInteraction: (AcpInteraction) -> Unit,
        private val onTextUpdate: (String) -> Unit,
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        private val updates = mutableListOf<String>()
        private var requestId = SESSION_PROMPT_REQUEST_ID

        @Synchronized
        fun prompt(task: String): AcpDelegationResult {
            require(task.isNotBlank()) { "ACP prompt must not be empty." }
            check(!closed.get()) { "ACP collaboration session is closed." }
            cancellation.throwIfCancelled()
            val promptResult =
                transport.request(
                    SESSION_PROMPT_METHOD,
                    promptParams(sessionId, task),
                    requestId++,
                    timeoutMillis,
                    onNotification = { notification ->
                        extractInteraction(notification)?.let(onInteraction)
                        extractTextUpdate(notification)?.let { update ->
                            synchronized(updates) { updates += update }
                            onTextUpdate(update)
                        }
                    },
                    onServerRequest = onInteraction,
                )
            cancellation.throwIfCancelled()
            val summary =
                synchronized(updates) {
                    updates.joinToString(separator = "").trim()
                }.ifBlank { promptResult.toString() }
            QDLog.debug(logger) { "ACP collaboration prompt completed for ${agent.name} (${agent.id}), session $sessionId" }
            return AcpDelegationResult(agent, "completed", sessionId, summary, synchronized(updates) { updates.size })
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            cancellation.clear(transport)
            transport.close()
        }
    }

    private fun openTransport(
        agent: AcpAgentDto,
        timeoutMillis: Long,
    ): AcpTransport {
        if (agent.executablePath.startsWith(TCP_PREFIX)) {
            val address = agent.executablePath.removePrefix(TCP_PREFIX)
            val host = address.substringBeforeLast(':')
            val port =
                address.substringAfterLast(':').toIntOrNull()
                    ?: error("Invalid ACP TCP endpoint: ${agent.executablePath}")
            val socket = socketFactory()
            socket.connect(InetSocketAddress(host, port), timeoutMillis.toInt())
            socket.soTimeout = timeoutMillis.toInt()
            return AcpTransport(
                reader = socket.getInputStream().bufferedReader(),
                writer = socket.getOutputStream().bufferedWriter(),
                closeAction = socket::close,
                mapper = mapper,
            )
        }
        val executable = File(agent.executablePath)
        require(executable.isFile && executable.canExecute()) { "ACP executable is unavailable: ${agent.executablePath}" }
        val process = processFactory(listOf(executable.absolutePath) + agent.command.drop(1), agent.environment)
        return AcpTransport(
            reader = process.inputStream.bufferedReader(),
            writer = process.outputStream.bufferedWriter(),
            closeAction = {
                process.destroyForcibly()
                process.waitFor(PROCESS_STOP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            },
            mapper = mapper,
        )
    }

    private fun initializeParams(): Map<String, Any> =
        mapOf(
            "protocolVersion" to ACP_PROTOCOL_VERSION,
            "clientInfo" to mapOf("name" to "quanta-ai-plugin", "version" to "0"),
            "clientCapabilities" to emptyMap<String, Any>(),
        )

    private fun sessionNewParams(workspacePath: String?): Map<String, Any> =
        mapOf(
            "cwd" to requireNotNull(workspacePath?.takeIf(String::isNotBlank)) { "ACP delegation requires a project workspace path." },
            "mcpServers" to emptyList<Any>(),
        )

    private fun promptParams(
        sessionId: String,
        task: String,
    ): Map<String, Any> =
        mapOf(
            "sessionId" to sessionId,
            "prompt" to
                listOf(
                    mapOf(
                        "type" to "text",
                        "text" to task,
                    ),
                ),
        )

    private fun extractTextUpdate(notification: JsonNode): String? {
        if (notification.path("method").asText() != SESSION_UPDATE_METHOD) return null
        val update = notification.path("params").path("update")
        val content = update.path("content")
        return content.path("text").asText().takeIf(String::isNotBlank)
    }

    private fun extractInteraction(notification: JsonNode): AcpInteraction? {
        val method = notification.path("method").asText()
        val text =
            notification
                .path("params")
                .path("update")
                .path("content")
                .path("text")
                .asText()
                .trim()
        return AcpInteraction.from(method = method, text = text)
    }

    private fun failure(
        agent: AcpAgentDto,
        message: String,
    ): AcpDelegationResult {
        QDLog.debug(logger) { "ACP delegation failed for ${agent.name} (${agent.id}): $message" }
        return AcpDelegationResult(agent = agent, status = "error", message = message)
    }

    data class AcpInteraction(
        val type: Type,
        val instructions: String,
    ) {
        enum class Type {
            AUTHENTICATION,
            PERMISSION,
            USER_INPUT,
        }

        companion object {
            fun from(
                method: String,
                text: String,
            ): AcpInteraction? {
                val normalized = "$method $text".lowercase()
                val type =
                    when {
                        normalized.contains("auth") || normalized.contains("login") || normalized.contains("sign in") -> Type.AUTHENTICATION
                        normalized.contains("permission") || normalized.contains("approval") -> Type.PERMISSION
                        normalized.contains("user_input") || normalized.contains("user input") -> Type.USER_INPUT
                        else -> return null
                    }
                val instructions =
                    when (type) {
                        Type.AUTHENTICATION -> {
                            "Complete sign-in in the external ACP agent's own window or terminal. " +
                                "Quanta cannot display or collect its credentials. The task should resume automatically after sign-in."
                        }

                        Type.PERMISSION -> {
                            text.takeIf(String::isNotBlank)
                                ?: "The external agent is waiting for an approval."
                        }

                        Type.USER_INPUT -> {
                            text.takeIf(String::isNotBlank)
                                ?: "The external agent is waiting for input."
                        }
                    }
                return AcpInteraction(type, instructions)
            }
        }
    }

    data class AcpDelegationResult(
        val agent: AcpAgentDto,
        val status: String,
        val sessionId: String? = null,
        val summary: String? = null,
        val updateCount: Int = 0,
        val message: String? = null,
    )

    internal class AcpTransport(
        private val reader: BufferedReader,
        private val writer: BufferedWriter,
        private val closeAction: () -> Unit,
        private val mapper: ObjectMapper,
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        private val readExecutor =
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "acp-response-reader") }

        fun request(
            method: String,
            params: Map<String, Any>,
            requestId: Int,
            timeoutMillis: Long,
            onNotification: (JsonNode) -> Unit = {},
            onServerRequest: (AcpInteraction) -> Unit = {},
        ): JsonNode {
            writer.write(
                mapper.writeValueAsString(
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to requestId,
                        "method" to method,
                        "params" to params,
                    ),
                ),
            )
            writer.newLine()
            writer.flush()
            while (true) {
                val line = readLine(timeoutMillis)
                val message = mapper.readTree(line)
                if (message.has("method")) {
                    if (message.has("id")) {
                        AcpInteraction.from(message.path("method").asText(), "")?.let(onServerRequest)
                        respondUnsupportedServerRequest(message)
                    } else {
                        onNotification(message)
                    }
                    continue
                }
                if (message.path("id").asInt(Int.MIN_VALUE) != requestId) continue
                message.path("error").takeIf { !it.isMissingNode && !it.isNull }?.let {
                    error("ACP $method failed: ${it.path("message").asText("unknown error")}")
                }
                return message.path("result").takeIf { !it.isMissingNode }
                    ?: error("ACP $method returned no result.")
            }
        }

        private fun readLine(timeoutMillis: Long): String {
            val future =
                readExecutor.submit<String> { reader.readLine() ?: error("ACP connection closed unexpectedly.") }
            return try {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (error: TimeoutException) {
                future.cancel(true)
                throw IllegalStateException("ACP response timed out after ${timeoutMillis}ms.", error)
            }
        }

        private fun respondUnsupportedServerRequest(message: JsonNode) {
            writer.write(
                mapper.writeValueAsString(
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to message.path("id").asText(),
                        "error" to
                            mapOf(
                                "code" to -32601,
                                "message" to "Quanta cannot respond to interactive ACP requests yet.",
                            ),
                    ),
                ),
            )
            writer.newLine()
            writer.flush()
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            readExecutor.shutdownNow()
            runCatching { reader.close() }
            runCatching { writer.close() }
            closeAction()
        }
    }

    class CancellationSignal {
        private val cancelled = AtomicBoolean(false)
        private val activeTransport = AtomicReference<Closeable?>(null)

        fun cancel() {
            cancelled.set(true)
            activeTransport.getAndSet(null)?.let { transport -> runCatching(transport::close) }
        }

        fun register(transport: Closeable) {
            if (!activeTransport.compareAndSet(null, transport)) {
                error("ACP delegation already has an active transport.")
            }
            if (cancelled.get()) cancel()
        }

        fun clear(transport: Closeable) {
            activeTransport.compareAndSet(transport, null)
        }

        fun throwIfCancelled() {
            check(!cancelled.get()) { "ACP delegation cancelled." }
        }
    }

    companion object {
        private val logger = Logger.getInstance(AcpDelegationService::class.java)
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val PROCESS_STOP_TIMEOUT_MILLIS = 500L
        private const val ACP_PROTOCOL_VERSION = 1
        private const val TCP_PREFIX = "tcp://"
        private const val INITIALIZE_METHOD = "initialize"
        private const val SESSION_NEW_METHOD = "session/new"
        private const val SESSION_PROMPT_METHOD = "session/prompt"
        private const val SESSION_UPDATE_METHOD = "session/update"
        private const val INITIALIZE_REQUEST_ID = 1
        private const val SESSION_NEW_REQUEST_ID = 2
        private const val SESSION_PROMPT_REQUEST_ID = 3

        private fun startProcess(
            command: List<String>,
            environment: Map<String, String>,
        ): Process =
            ProcessBuilder(command)
                .apply { this.environment().putAll(environment) }
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
    }
}
