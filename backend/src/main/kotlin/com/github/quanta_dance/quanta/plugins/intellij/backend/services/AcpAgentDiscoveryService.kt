// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpManualAgentDto
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Finds ACP agents available to the backend process and verifies them with an ACP handshake.
 *
 * Discovery never invokes a shell. Application candidates are represented as executable paths and
 * TCP candidates use newline-delimited JSON-RPC. Each probe is short-lived and is terminated or
 * closed after its initialize response.
 */
class AcpAgentDiscoveryService(
    private val environment: Map<String, String> = System.getenv(),
    private val manualAgents: List<AcpManualAgentDto> = emptyList(),
    private val processFactory: (List<String>) -> Process = ::startProcess,
    private val socketFactory: () -> Socket = ::Socket,
) {
    fun discover(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): List<AcpAgentDto> {
        val applicationCandidates = defaultApplicationCandidates() + manualApplicationCandidates()
        QDLog.debug(logger) {
            "ACP discovery started for ${applicationCandidates.size} application and " +
                "${manualTcpCandidates().size} TCP candidate(s)"
        }
        return (
            applicationCandidates.mapNotNull { command -> discoverApplication(command, timeoutMillis) } +
                manualTcpCandidates().mapNotNull { endpoint -> discoverTcp(endpoint, timeoutMillis) }
        ).distinctBy { it.executablePath }
            .also { agents ->
                QDLog.debug(logger) {
                    "ACP discovery completed: ${agents.size} compatible agent(s) found" +
                        agents.joinToString(prefix = " [", postfix = "]") { agent ->
                            "${agent.name} ${agent.version ?: "(unknown version)"} at ${agent.executablePath}"
                        }
                }
            }
    }

    private fun discoverApplication(
        command: List<String>,
        timeoutMillis: Long,
    ): AcpAgentDto? {
        val executablePath =
            resolveExecutable(command.first()) ?: run {
                QDLog.debug(logger) { "ACP candidate not found on PATH: ${command.joinToString(" ")}" }
                return null
            }
        QDLog.debug(logger) {
            "ACP candidate found: ${command.joinToString(" ")} (resolved to $executablePath); starting handshake"
        }
        val process =
            runCatching { processFactory(listOf(executablePath) + command.drop(1)) }
                .onFailure { error ->
                    QDLog.debug(logger) {
                        "ACP handshake could not start for ${command.joinToString(" ")}: ${error.message}"
                    }
                }.getOrNull() ?: return null
        return try {
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(INITIALIZE_REQUEST)
                writer.newLine()
                writer.flush()
            }
            val response =
                readProcessLineWithTimeout(process, timeoutMillis) ?: run {
                    QDLog.debug(logger) {
                        "ACP handshake timed out after ${timeoutMillis}ms: ${command.joinToString(" ")}"
                    }
                    return null
                }
            agentFromResponse(response, command, executablePath)
        } catch (error: Exception) {
            QDLog.debug(logger) { "ACP handshake failed for ${command.joinToString(" ")}: ${error.message}" }
            null
        } finally {
            process.destroyForcibly()
            process.waitFor(100, TimeUnit.MILLISECONDS)
        }
    }

    private fun discoverTcp(
        endpoint: AcpManualAgentDto,
        timeoutMillis: Long,
    ): AcpAgentDto? {
        val host = endpoint.host ?: return null
        val port = endpoint.port ?: return null
        val endpointPath = "tcp://$host:$port"
        QDLog.debug(logger) { "ACP TCP candidate found: $endpointPath; starting handshake" }
        return runCatching {
            socketFactory().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMillis.toInt())
                socket.soTimeout = timeoutMillis.toInt()
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write(INITIALIZE_REQUEST)
                writer.newLine()
                writer.flush()
                val response = socket.getInputStream().bufferedReader().readLine()
                agentFromResponse(response, listOf("tcp", host, port.toString()), endpointPath, endpoint.name)
            }
        }.onFailure { error ->
            QDLog.debug(logger) { "ACP TCP handshake failed for $endpointPath: ${error.message}" }
        }.getOrNull()
    }

    private fun agentFromResponse(
        response: String,
        command: List<String>,
        endpointPath: String,
        configuredName: String? = null,
    ): AcpAgentDto? {
        val protocolVersion =
            PROTOCOL_VERSION
                .find(response)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        if (!response.contains("\"result\"") || protocolVersion == null) {
            QDLog.debug(logger) { "ACP handshake returned a non-ACP initialize response: ${command.joinToString(" ")}" }
            return null
        }
        return AcpAgentDto(
            id = UUID.nameUUIDFromBytes((endpointPath + command).toByteArray()).toString(),
            name = AGENT_NAME.find(response)?.groupValues?.get(1) ?: configuredName ?: command.first(),
            command = command,
            executablePath = endpointPath,
            version = AGENT_VERSION.find(response)?.groupValues?.get(1),
            protocolVersion = protocolVersion,
        ).also { agent ->
            QDLog.debug(logger) {
                "ACP handshake succeeded: ${agent.name} ${agent.version ?: "(unknown version)"} " +
                    "using ${command.joinToString(" ")} (protocol ${agent.protocolVersion})"
            }
        }
    }

    private fun readProcessLineWithTimeout(
        process: Process,
        timeoutMillis: Long,
    ): String? {
        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val thread =
            Thread {
                runCatching { reader.readLine() }.getOrNull()?.let(output::append)
            }.apply {
                isDaemon = true
                start()
            }
        thread.join(timeoutMillis)
        if (thread.isAlive) return null
        return output.toString().takeIf { it.isNotBlank() }
    }

    private fun manualApplicationCandidates(): List<List<String>> =
        manualAgents.mapNotNull { endpoint ->
            endpoint.executable
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(::listOf)
        }

    private fun manualTcpCandidates(): List<AcpManualAgentDto> =
        manualAgents.filter { endpoint ->
            endpoint.host?.isNotBlank() == true && endpoint.port in 1..65_535
        }

    private fun resolveExecutable(command: String): String? {
        val file = File(command)
        if (file.isAbsolute || command.contains(File.separator)) {
            return file.takeIf { it.isFile && it.canExecute() }?.absolutePath
        }
        val pathEntries = environment["PATH"]?.split(File.pathSeparator).orEmpty()
        val suffixes =
            if (System.getProperty("os.name").contains("windows", ignoreCase = true)) {
                listOf("", ".exe", ".cmd", ".bat")
            } else {
                listOf("")
            }
        return pathEntries
            .asSequence()
            .flatMap { directory -> suffixes.asSequence().map { suffix -> File(directory, command + suffix) } }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    companion object {
        private val logger = Logger.getInstance(AcpAgentDiscoveryService::class.java)
        const val DEFAULT_TIMEOUT_MILLIS = 1_500L
        private const val INITIALIZE_REQUEST =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientInfo":{"name":"quanta-ai-plugin","version":"0"},"clientCapabilities":{}}}"""
        private val PROTOCOL_VERSION = Regex("\\\"protocolVersion\\\"\\s*:\\s*(\\d+)")
        private val AGENT_NAME = Regex("\\\"name\\\"\\s*:\\s*\\\"([^\"]+)")
        private val AGENT_VERSION = Regex("\\\"version\\\"\\s*:\\s*\\\"([^\"]+)")

        private fun defaultApplicationCandidates(): List<List<String>> =
            listOf(
                listOf("claude-agent-acp"),
                listOf("codex-acp"),
                listOf("gemini-acp"),
                listOf("goose", "acp"),
                listOf("opencode", "acp"),
                listOf("kiro-cli", "acp"),
            )

        private fun startProcess(command: List<String>): Process =
            ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()
    }
}
