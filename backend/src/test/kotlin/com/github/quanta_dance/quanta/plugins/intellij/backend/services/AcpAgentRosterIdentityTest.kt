// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AcpAgentRosterIdentityTest {
    @Test
    fun `uses paired peer identity instead of transient endpoint`() {
        val firstConnection = pairedAgent(id = "old", endpoint = "tcp://127.0.0.1:40101")
        val reconnectedPeer = pairedAgent(id = "new", endpoint = "tcp://127.0.0.1:40102")

        assertEquals(acpRosterIdentity(firstConnection), acpRosterIdentity(reconnectedPeer))
    }

    @Test
    fun `keeps distinct paired peers with matching display names`() {
        val firstPeer = pairedAgent(id = "first", endpoint = "tcp://127.0.0.1:40101", peerIdentity = "peer-one")
        val secondPeer = pairedAgent(id = "second", endpoint = "tcp://127.0.0.1:40102", peerIdentity = "peer-two")

        assertNotEquals(acpRosterIdentity(firstPeer), acpRosterIdentity(secondPeer))
    }

    @Test
    fun `uses agent id for non Quanta ACP agents`() {
        val firstAgent = AcpAgentDto("first", "ACP", listOf("acp"), "acp", protocolVersion = 1)
        val secondAgent = AcpAgentDto("second", "ACP", listOf("acp"), "acp", protocolVersion = 1)

        assertNotEquals(acpRosterIdentity(firstAgent), acpRosterIdentity(secondAgent))
    }

    private fun pairedAgent(
        id: String,
        endpoint: String,
        peerIdentity: String = "shared-peer",
    ) = AcpAgentDto(
        id = id,
        name = "Shared Quanta · project",
        command = listOf("quanta-acp"),
        executablePath = endpoint,
        protocolVersion = 1,
        peerIdentity = peerIdentity,
    )
}
