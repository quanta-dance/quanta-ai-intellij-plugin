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

/**
 * Runs one bounded delegation against an external ACP agent.
 *
 * Unlike [AcpAgentDiscoveryService], this service keeps the transport open for the complete ACP
 * lifecycle: initialize, session creation, prompt, streamed updates, and final response. The
 * transport is deliberately closed after the delegation completes; persistent multi-turn ACP
 * sessions can be added later without coupling them to the discovery probes.
 */
class AcpDelegationService(
    private val processFactory: (List<String>) -> Process = ::startProcess,
    private val socketFactory: () -> Socket = ::Socket,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    fun delegate(
        agent: AcpAgentDto,
        task: String,
        workspacePath: String?,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): AcpDelegationResult {
        require(task.isNotBlank()) { "Delegated ACP task must not be empty." }
        val delegation = openTransport(agent, timeoutMillis)
        delegation.use { transport ->
            QDLog.debug(logger) { "ACP delegation started for ${agent.name} (${agent.id})" }
            transport.request(INITIALIZE_METHOD, initializeParams(), INITIALIZE_REQUEST_ID, timeoutMillis)
            val session =
                transport.request(
                    SESSION_NEW_METHOD,
                    sessionNewParams(workspacePath),
                    SESSION_NEW_REQUEST_ID,
                    timeoutMillis,
                )
            val sessionId = session.path("sessionId").asText().takeIf(String::isNotBlank)
                ?: return failure(agent, "ACP agent did not return a session ID.")
            val updates = mutableListOf<String>()
            val promptResult =
                transport.request(
                    SESSION_PROMPT_METHOD,
                    promptParams(sessionId, task),
                    SESSION_PROMPT_REQUEST_ID,
                    timeoutMillis,
                    onNotification = { notification -> extractTextUpdate(notification)?.let(updates::add) },
                )
            val summary = updates.joinToString(separator = "").trim().ifBlank { promptResult.toString() }
            QDLog.debug(logger) {
                "ACP delegation completed for ${agent.name} (${agent.id}), session $sessionId, " +
                        "${updates.size} text update(s)"
            }
            return AcpDelegationResult(
                agent = agent,
                status = "completed",
                sessionId = sessionId,
                summary = summary,
                updateCount = updates.size,
            )
        }
    }

    private fun openTransport(
        agent: AcpAgentDto,
        timeoutMillis: Long,
    ): AcpTransport {
        if (agent.executablePath.startsWith(TCP_PREFIX)) {
            val address = agent.executablePath.removePrefix(TCP_PREFIX)
            val host = address.substringBeforeLast(':')
            val port = address.substringAfterLast(':').toIntOrNull()
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
        val process = processFactory(listOf(executable.absolutePath) + agent.command.drop(1))
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
        buildMap {
            workspacePath?.takeIf(String::isNotBlank)?.let { put("cwd", it) }
        }

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
                            "text" to "$READ_ONLY_INSTRUCTION\n\nDelegated task:\n$task",
                        ),
                    ),
        )

    private fun extractTextUpdate(notification: JsonNode): String? {
        if (notification.path("method").asText() != SESSION_UPDATE_METHOD) return null
        val update = notification.path("params").path("update")
        val content = update.path("content")
        return content.path("text").asText().takeIf(String::isNotBlank)
    }

    private fun failure(
        agent: AcpAgentDto,
        message: String,
    ): AcpDelegationResult {
        QDLog.debug(logger) { "ACP delegation failed for ${agent.name} (${agent.id}): $message" }
        return AcpDelegationResult(agent = agent, status = "error", message = message)
    }

    data class AcpDelegationResult(
        val agent: AcpAgentDto,
        val status: String,
        val sessionId: String? = null,
        val summary: String? = null,
        val updateCount: Int = 0,
        val message: String? = null,
    )

    private class AcpTransport(
        private val reader: BufferedReader,
        private val writer: BufferedWriter,
        private val closeAction: () -> Unit,
        private val mapper: ObjectMapper,
    ) : Closeable {
        private val readExecutor =
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "acp-response-reader") }

        fun request(
            method: String,
            params: Map<String, Any>,
            requestId: Int,
            timeoutMillis: Long,
            onNotification: (JsonNode) -> Unit = {},
        ): JsonNode {
            writer.write(
                mapper.writeValueAsString(
                    mapOf(
                        "jsonrpc" to "2.0",
                        "id" to requestId,
                        "method" to method,
                        "params" to params
                    )
                )
            )
            writer.newLine()
            writer.flush()
            while (true) {
                val line = readLine(timeoutMillis)
                val message = mapper.readTree(line)
                if (message.has("method") && !message.has("id")) {
                    onNotification(message)
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

        override fun close() {
            readExecutor.shutdownNow()
            runCatching { reader.close() }
            runCatching { writer.close() }
            closeAction()
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
        private const val READ_ONLY_INSTRUCTION =
            "This is a read-only delegated investigation. Do not modify files, run commands that mutate state, " +
                    "or access credentials. Return findings and actionable recommendations to the delegating agent."

        private fun startProcess(command: List<String>): Process =
            ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
    }
}
