// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
                        val sessionRequest = reader.readLine()
                        assertTrue(sessionRequest.contains("\"method\":\"session/new\""))
                        assertTrue(sessionRequest.contains("\"cwd\":\"/workspace\""))
                        assertTrue(sessionRequest.contains("\"mcpServers\":[]"))
                        writer.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"session-1\"}}")
                        writer.newLine()
                        writer.flush()
                        val prompt = reader.readLine()
                        assertTrue(prompt.contains("\"method\":\"session/prompt\""))
                        assertTrue(prompt.contains("Inspect the authentication flow."))
                        writer.write(
                            "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"session-1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"Investigation complete.\"}}}}",
                        )
                        writer.newLine()
                        writer.write("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\"}}")
                        writer.newLine()
                        writer.flush()
                    }
                }.apply { start() }

            val streamedUpdates = mutableListOf<String>()
            val result =
                AcpDelegationService().delegate(
                    agent = tcpAgent(server.localPort),
                    task = "Inspect the authentication flow.",
                    workspacePath = "/workspace",
                    timeoutMillis = 2_000,
                    onTextUpdate = streamedUpdates::add,
                )

            responder.join(2_000)
            assertEquals("completed", result.status)
            assertEquals("session-1", result.sessionId)
            assertEquals("Investigation complete.", result.summary)
            assertEquals(listOf("Investigation complete."), streamedUpdates)
            assertEquals(1, result.updateCount)
        }
    }

    @Test
    fun `retains a live ACP session for serialized follow-up prompts`() {
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
                        writer.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"session-1\"}}")
                        writer.newLine()
                        writer.flush()
                        assertTrue(reader.readLine().contains("\"id\":3"))
                        writer.write("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\"}}")
                        writer.newLine()
                        writer.flush()
                        val followUp = reader.readLine()
                        assertTrue(followUp.contains("\"id\":4"))
                        assertTrue(followUp.contains("Please check the tests too."))
                        writer.write(
                            "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"session-1\",\"update\":{\"content\":{\"type\":\"text\",\"text\":\"Tests confirm the finding.\"}}}}",
                        )
                        writer.newLine()
                        writer.write("{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"stopReason\":\"end_turn\"}}")
                        writer.newLine()
                        writer.flush()
                    }
                }.apply { start() }

            val session =
                AcpDelegationService().openSession(
                    agent = tcpAgent(server.localPort),
                    workspacePath = "/workspace",
                    timeoutMillis = 2_000,
                )
            session.use {
                assertEquals("completed", session.prompt("Inspect the project.").status)
                val result = session.prompt("Please check the tests too.")
                assertEquals("session-1", result.sessionId)
                assertEquals("Tests confirm the finding.", result.summary)
            }
            responder.join(2_000)
        }
    }

    @Test
    fun `reports an authentication interaction from an ACP update`() {
        ServerSocket(0).use { server ->
            val interactionReceived = CountDownLatch(1)
            var interaction: AcpDelegationService.AcpInteraction? = null
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
                        writer.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"session-1\"}}")
                        writer.newLine()
                        writer.flush()
                        reader.readLine()
                        writer.write(
                            "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"session-1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"Login with OpenCode to continue.\"}}}}",
                        )
                        writer.newLine()
                        writer.write("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\"}}")
                        writer.newLine()
                        writer.flush()
                    }
                }.apply { start() }

            AcpDelegationService().delegate(
                agent = tcpAgent(server.localPort),
                task = "Inspect the authentication flow.",
                workspacePath = "/workspace",
                timeoutMillis = 2_000,
                onInteraction = {
                    interaction = it
                    interactionReceived.countDown()
                },
            )

            assertTrue(interactionReceived.await(2, TimeUnit.SECONDS))
            assertEquals(AcpDelegationService.AcpInteraction.Type.AUTHENTICATION, interaction?.type)
            assertEquals(
                "Complete sign-in in the external ACP agent's own window or terminal. " +
                    "Quanta cannot display or collect its credentials. The task should resume automatically after sign-in.",
                interaction?.instructions,
            )
            responder.join(2_000)
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
                    workspacePath = "/workspace",
                    timeoutMillis = 2_000,
                )

            responder.join(2_000)
            assertEquals("error", result.status)
            assertTrue(result.message!!.contains("session ID"))
        }
    }

    @Test
    fun `cancellation closes an active ACP transport without waiting for its timeout`() {
        ServerSocket(0).use { server ->
            val requestReceived = CountDownLatch(1)
            val connectionClosed = CountDownLatch(1)
            val responder =
                Thread {
                    server.accept().use { socket ->
                        socket.getInputStream().bufferedReader().readLine()
                        requestReceived.countDown()
                        while (socket.getInputStream().read() != -1) {
                            // Wait for the client to close the transport.
                        }
                        connectionClosed.countDown()
                    }
                }.apply { start() }
            val cancellation = AcpDelegationService.CancellationSignal()
            val worker =
                Thread {
                    runCatching {
                        AcpDelegationService().delegate(
                            agent = tcpAgent(server.localPort),
                            task = "Inspect the authentication flow.",
                            workspacePath = "/workspace",
                            timeoutMillis = 30_000,
                            cancellation = cancellation,
                        )
                    }
                }.apply { start() }

            assertTrue(requestReceived.await(2, TimeUnit.SECONDS))
            cancellation.cancel()

            assertTrue(connectionClosed.await(2, TimeUnit.SECONDS))
            worker.join(2_000)
            assertTrue(!worker.isAlive)
            responder.join(2_000)
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
