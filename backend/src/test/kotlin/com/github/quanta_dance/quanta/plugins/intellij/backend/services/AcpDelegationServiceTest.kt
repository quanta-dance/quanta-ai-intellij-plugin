// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcpDelegationServiceTest {
    @Test
    fun `delegates over TCP and collects streamed text updates`() {
        ServerSocket(0).use { server ->
            val responder =
                Thread {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val writer = socket.getOutputStream().bufferedWriter()
                        assertTrue(reader.readLine().contains("\"method\":\"initialize\""))
                        writer.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1}}")
                        writer.newLine()
                        writer.flush()
                        assertTrue(reader.readLine().contains("\"method\":\"session/new\""))
                        writer.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"session-1\"}}")
                        writer.newLine()
                        writer.flush()
                        val prompt = reader.readLine()
                        assertTrue(prompt.contains("\"method\":\"session/prompt\""))
                        assertTrue(prompt.contains("read-only delegated investigation"))
                        writer.write(
                            "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"session-1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"Investigation complete.\"}}}}",
                        )
                        writer.newLine()
                        writer.write("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\"}}")
                        writer.newLine()
                        writer.flush()
                    }
                }.apply { start() }

            val result =
                AcpDelegationService().delegate(
                    agent = tcpAgent(server.localPort),
                    task = "Inspect the authentication flow.",
                    workspacePath = "/workspace",
                    timeoutMillis = 2_000,
                )

            responder.join(2_000)
            assertEquals("completed", result.status)
            assertEquals("session-1", result.sessionId)
            assertEquals("Investigation complete.", result.summary)
            assertEquals(1, result.updateCount)
        }
    }

    @Test
    fun `returns an error result when session creation lacks session id`() {
        ServerSocket(0).use { server ->
            val responder =
                Thread {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val writer = socket.getOutputStream().bufferedWriter()
                        reader.readLine()
                        writer.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1}}")
                        writer.newLine()
                        writer.flush()
                        reader.readLine()
                        writer.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}")
                        writer.newLine()
                        writer.flush()
                    }
                }.apply { start() }

            val result =
                AcpDelegationService().delegate(
                    agent = tcpAgent(server.localPort),
                    task = "Inspect the authentication flow.",
                    workspacePath = null,
                    timeoutMillis = 2_000,
                )

            responder.join(2_000)
            assertEquals("error", result.status)
            assertTrue(result.message!!.contains("session ID"))
        }
    }

    private fun tcpAgent(port: Int): AcpAgentDto =
        AcpAgentDto(
            id = "test-agent",
            name = "Test ACP",
            command = listOf("tcp", "127.0.0.1", port.toString()),
            executablePath = "tcp://127.0.0.1:$port",
            protocolVersion = 1,
        )
}
