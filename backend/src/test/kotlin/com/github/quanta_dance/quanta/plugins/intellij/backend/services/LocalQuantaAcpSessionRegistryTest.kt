// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.LocalQuantaAcpSessionDto
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalQuantaAcpSessionRegistryTest {
    @Test
    fun `returns only active sessions other than the local session`() {
        val registry = LocalQuantaAcpSessionRegistry(Files.createTempDirectory("quanta-acp-sessions"))
        registry.publish(session("local", "Current project", Long.MAX_VALUE))
        registry.publish(session("peer", "Other project", Long.MAX_VALUE))
        registry.publish(session("expired", "Expired project", 0))

        val sessions = registry.available(excludingPeerIdentity = "local")

        assertEquals(listOf("peer"), sessions.map(LocalQuantaAcpSessionDto::peerIdentity))
    }

    @Test
    fun `hides current-project and legacy local sessions but keeps other projects in the same IDE process`() {
        val registry =
            LocalQuantaAcpSessionRegistry(
                directory = Files.createTempDirectory("quanta-acp-sessions"),
                isPublisherProcessAlive = { true },
            )
        registry.publish(
            session(
                "stale-current",
                "Current project",
                Long.MAX_VALUE,
                publisherProjectPath = "/projects/current",
                publisherProcessId = 42,
            ),
        )
        registry.publish(session("legacy-current", "Current project", Long.MAX_VALUE, publisherProcessId = 42))
        registry.publish(
            session(
                "other-project",
                "Other project",
                Long.MAX_VALUE,
                publisherProjectPath = "/projects/other",
                publisherProcessId = 42,
            ),
        )

        val sessions =
            registry.available(
                excludingPeerIdentity = "current",
                excludingProjectPath = "/projects/current",
                excludingLegacyPublisherProcessId = 42,
            )

        assertEquals(listOf("other-project"), sessions.map(LocalQuantaAcpSessionDto::peerIdentity))
    }

    @Test
    fun `removes unavailable publisher process entries`() {
        val directory = Files.createTempDirectory("quanta-acp-sessions")
        val registry =
            LocalQuantaAcpSessionRegistry(
                directory = directory,
                isPublisherProcessAlive = { processId -> processId != 42L },
            )
        registry.publish(session("closed-ide", "Closed IDE", Long.MAX_VALUE, publisherProcessId = 42))
        registry.publish(session("open-ide", "Open IDE", Long.MAX_VALUE, publisherProcessId = 43))

        val sessions = registry.available(excludingPeerIdentity = "local")

        assertEquals(listOf("open-ide"), sessions.map(LocalQuantaAcpSessionDto::peerIdentity))
        assertEquals(false, Files.exists(directory.resolve("closed-ide.json")))
    }

    @Test
    fun `keeps sessions discoverable while their publisher process is alive`() {
        val directory = Files.createTempDirectory("quanta-acp-sessions")
        val registry =
            LocalQuantaAcpSessionRegistry(
                directory = directory,
                isPublisherProcessAlive = { true },
            )
        registry.publish(session("pending-session", "Open IDE", Long.MAX_VALUE, publisherProcessId = 42))
        registry.publish(session("reachable-session", "Other IDE", Long.MAX_VALUE, publisherProcessId = 43))

        val sessions = registry.available(excludingPeerIdentity = "local")

        assertEquals(listOf("Open IDE", "Other IDE"), sessions.map(LocalQuantaAcpSessionDto::projectName))
        assertEquals(true, Files.exists(directory.resolve("pending-session.json")))
    }

    @Test
    fun `removes unpublished session`() {
        val registry = LocalQuantaAcpSessionRegistry(Files.createTempDirectory("quanta-acp-sessions"))
        registry.publish(session("peer", "Other project", Long.MAX_VALUE))

        registry.remove("peer")

        assertEquals(emptyList(), registry.available(excludingPeerIdentity = "local"))
    }

    private fun session(
        peerIdentity: String,
        projectName: String,
        expiresAtMillis: Long,
        publisherProjectPath: String? = null,
        publisherProcessId: Long? = null,
    ) = LocalQuantaAcpSessionDto(
        peerIdentity = peerIdentity,
        projectName = projectName,
        invite = "quanta-acp://join?peer=$peerIdentity",
        expiresAtMillis = expiresAtMillis,
        publisherProjectPath = publisherProjectPath,
        publisherProcessId = publisherProcessId,
    )
}
