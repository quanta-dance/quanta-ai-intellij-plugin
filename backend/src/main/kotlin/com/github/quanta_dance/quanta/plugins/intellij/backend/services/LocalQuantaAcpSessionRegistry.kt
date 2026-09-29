// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.LocalQuantaAcpSessionDto
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * File-backed registry for explicitly shared Quanta ACP sessions on the same operating-system user account.
 *
 * Entries contain short-lived, localhost-only one-time invites. They are removed when sharing stops and pruned
 * when expired, so this registry never advertises arbitrary IDE instances or remote endpoints.
 */
internal class LocalQuantaAcpSessionRegistry(
    private val directory: Path = Path.of(System.getProperty("user.home"), ".quanta", "acp-sessions"),
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    private val isPublisherProcessAlive: (Long) -> Boolean = { processId ->
        ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false)
    },
) {
    fun publish(session: LocalQuantaAcpSessionDto) {
        Files.createDirectories(directory)
        val destination = entryPath(session.peerIdentity)
        val temporary = destination.resolveSibling(".${destination.fileName}.${UUID.randomUUID()}.tmp")
        Files.writeString(temporary, mapper.writeValueAsString(session), StandardCharsets.UTF_8)
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun remove(peerIdentity: String) {
        Files.deleteIfExists(entryPath(peerIdentity))
    }

    fun available(
        excludingPeerIdentity: String,
        excludingProjectPath: String? = null,
        excludingLegacyPublisherProcessId: Long? = null,
    ): List<LocalQuantaAcpSessionDto> {
        if (!Files.isDirectory(directory)) return emptyList()
        val now = System.currentTimeMillis()
        return Files.list(directory).use { entries ->
            entries
                .iterator()
                .asSequence()
                .filter { entry -> entry.fileName.toString().endsWith(".json") }
                .mapNotNull(::read)
                .filter { session ->
                    val isExpired = session.expiresAtMillis <= now
                    val isStaleProcess =
                        session.publisherProcessId?.let { processId -> !isPublisherProcessAlive(processId) } ?: false
                    if (!isExpired && !isStaleProcess) {
                        true
                    } else {
                        Files.deleteIfExists(entryPath(session.peerIdentity))
                        false
                    }
                }.filterNot { session ->
                    session.peerIdentity == excludingPeerIdentity ||
                        (excludingProjectPath != null && session.publisherProjectPath == excludingProjectPath) ||
                        (
                            excludingLegacyPublisherProcessId != null &&
                                session.publisherProjectPath == null &&
                                session.publisherProcessId == excludingLegacyPublisherProcessId
                        )
                }.sortedBy(LocalQuantaAcpSessionDto::projectName)
                .toList()
        }
    }

    private fun read(path: Path): LocalQuantaAcpSessionDto? =
        runCatching {
            mapper.readValue(Files.readString(path, StandardCharsets.UTF_8), LocalQuantaAcpSessionDto::class.java)
        }.getOrNull()

    private fun entryPath(peerIdentity: String): Path = directory.resolve("$peerIdentity.json")
}
