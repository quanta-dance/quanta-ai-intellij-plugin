// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpManualAgentDto
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcpAgentDiscoveryServiceTest {
    @Test
    fun `discovers PATH executable after ACP initialize handshake`() {
        val directory = Files.createTempDirectory("acp-agent")
        val script = directory.resolve("claude-agent-acp")
        Files.writeString(
            script,
            """
            #!/bin/sh
            read request
            echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentInfo":{"name":"Test ACP","version":"1.2.3"}}}'
            """.trimIndent(),
        )
        assertTrue(script.toFile().setExecutable(true))

        val agents =
            AcpAgentDiscoveryService(
                environment = mapOf("PATH" to directory.toString()),
                jetBrainsConfigService = absentJetBrainsConfig(),
            ).discover()

        assertEquals(1, agents.size)
        assertEquals("Test ACP", agents.single().name)
        assertEquals("1.2.3", agents.single().version)
        assertEquals(1, agents.single().protocolVersion)
        assertEquals(listOf("claude-agent-acp"), agents.single().command)
    }

    @Test
    fun `waits for a cold-starting ACP adapter within the default handshake timeout`() {
        val directory = Files.createTempDirectory("slow-acp-agent")
        val script = directory.resolve("claude-agent-acp")
        Files.writeString(
            script,
            """
            #!/bin/sh
            read request
            sleep 2
            echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentInfo":{"name":"Slow ACP"}}}'
            """.trimIndent(),
        )
        assertTrue(script.toFile().setExecutable(true))

        val agents =
            AcpAgentDiscoveryService(
                environment = mapOf("PATH" to directory.toString()),
                jetBrainsConfigService = absentJetBrainsConfig(),
            ).discover()

        assertEquals("Slow ACP", agents.single().name)
    }

    @Test
    fun `ignores executable that does not return an ACP result`() {
        val directory = Files.createTempDirectory("not-acp-agent")
        val script = directory.resolve("claude-agent-acp")
        Files.writeString(
            script,
            """
            #!/bin/sh
            read request
            echo 'not an ACP response'
            """.trimIndent(),
        )
        assertTrue(script.toFile().setExecutable(true))

        val agents =
            AcpAgentDiscoveryService(
                environment = mapOf("PATH" to directory.toString()),
                jetBrainsConfigService = absentJetBrainsConfig(),
            ).discover()

        assertTrue(agents.isEmpty())
    }

    @Test
    fun `discovers a manually configured ACP application outside PATH`() {
        val script = Files.createTempFile("manual-acp-agent", ".sh")
        Files.writeString(
            script,
            """
            #!/bin/sh
            read request
            echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentInfo":{"name":"Manual ACP"}}}'
            """.trimIndent(),
        )
        assertTrue(script.toFile().setExecutable(true))

        val agents =
            AcpAgentDiscoveryService(
                environment = emptyMap(),
                manualAgents = listOf(AcpManualAgentDto(name = "Configured agent", executable = script.toString())),
                jetBrainsConfigService = absentJetBrainsConfig(),
            ).discover()

        assertEquals(1, agents.size)
        assertEquals("Manual ACP", agents.single().name)
        assertEquals(script.toFile().absolutePath, agents.single().executablePath)
    }

    @Test
    fun `discovers JetBrains configured ACP application with arguments and environment`() {
        val script = Files.createTempFile("jetbrains-acp-agent", ".sh")
        Files.writeString(
            script,
            """
            #!/bin/sh
            [ "${'$'}ACP_CONFIG_TEST" = "configured" ] || exit 1
            [ "${'$'}1" = "rig" ] || exit 1
            read request
            echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentInfo":{"name":"Configured ACP"}}}'
            """.trimIndent(),
        )
        assertTrue(script.toFile().setExecutable(true))
        val config = Files.createTempFile("jetbrains-acp", ".json")
        Files.writeString(
            config,
            """
            {
              "agent_servers": {
                "Codemaster": {
                  "command": "$script",
                  "args": ["rig"],
                  "env": {"ACP_CONFIG_TEST": "configured"}
                }
              }
            }
            """.trimIndent(),
        )

        val agents =
            AcpAgentDiscoveryService(
                environment = emptyMap(),
                jetBrainsConfigService = JetBrainsAcpConfigService(config.toFile()),
            ).discover()

        assertEquals(1, agents.size)
        assertEquals(listOf(script.toString(), "rig"), agents.single().command)
        assertEquals(mapOf("ACP_CONFIG_TEST" to "configured"), agents.single().environment)
    }

    @Test
    fun `discovers a manually configured ACP TCP endpoint`() {
        ServerSocket(0).use { server ->
            val responder =
                Thread {
                    server.accept().use { socket ->
                        socket.getInputStream().bufferedReader().readLine()
                        val writer = socket.getOutputStream().bufferedWriter()
                        writer.write(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1,\"agentInfo\":{\"name\":\"TCP ACP\"}}}",
                        )
                        writer.newLine()
                        writer.flush()
                    }
                }.apply { start() }

            val agents =
                AcpAgentDiscoveryService(
                    environment = emptyMap(),
                    manualAgents =
                        listOf(
                            AcpManualAgentDto(
                                name = "Configured TCP",
                                host = "127.0.0.1",
                                port = server.localPort,
                            ),
                        ),
                    jetBrainsConfigService = absentJetBrainsConfig(),
                ).discover()

            responder.join(1_000)
            assertEquals(1, agents.size)
            assertEquals("TCP ACP", agents.single().name)
            assertEquals("tcp://127.0.0.1:${server.localPort}", agents.single().executablePath)
        }
    }

    private fun absentJetBrainsConfig(): JetBrainsAcpConfigService =
        JetBrainsAcpConfigService(Files.createTempDirectory("absent-jetbrains-acp").resolve("acp.json").toFile())
}
