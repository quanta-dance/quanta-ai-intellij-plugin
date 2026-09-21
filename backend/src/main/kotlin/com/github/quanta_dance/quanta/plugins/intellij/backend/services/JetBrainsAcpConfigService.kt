// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Loads ACP application definitions configured by JetBrains-compatible IDEs in `~/.jetbrains/acp.json`.
 *
 * The file is user-managed. Invalid entries are ignored individually so one malformed agent does not
 * prevent discovery of other configured agents or built-in PATH candidates.
 */
class JetBrainsAcpConfigService(
    private val configFile: File = File(System.getProperty("user.home"), ".jetbrains/acp.json"),
    private val mapper: ObjectMapper =
        ObjectMapper(
            JsonFactory
                .builder()
                .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .build(),
        ),
) {
    fun loadApplicationCandidates(): List<ApplicationCandidate> {
        if (!configFile.isFile) return emptyList()
        val root =
            runCatching { mapper.readTree(configFile) }
                .onFailure { error ->
                    QDLog.warn(logger) {
                        "Could not read JetBrains ACP configuration at ${configFile.absolutePath}: ${error.message}"
                    }
                }.getOrNull() ?: return emptyList()
        val servers = root.path("agent_servers")
        if (!servers.isObject) {
            QDLog.warn(logger) { "JetBrains ACP configuration has no agent_servers object: ${configFile.absolutePath}" }
            return emptyList()
        }
        return servers
            .fields()
            .asSequence()
            .mapNotNull { (name, definition) ->
                val command = definition.path("command").asText().trim()
                if (command.isBlank()) {
                    QDLog.warn(logger) { "Ignoring JetBrains ACP agent '$name': command is missing" }
                    return@mapNotNull null
                }
                val arguments =
                    definition
                        .path("args")
                        .takeIf { it.isArray }
                        ?.mapNotNull { argument ->
                            argument.takeIf { it.isTextual }?.asText()
                        }.orEmpty()
                val environment =
                    definition
                        .path("env")
                        .takeIf { it.isObject }
                        ?.fields()
                        ?.asSequence()
                        ?.mapNotNull { (key, value) ->
                            value.takeIf { it.isTextual }?.asText()?.let { key to it }
                        }?.toMap()
                        .orEmpty()
                ApplicationCandidate(name = name, command = listOf(command) + arguments, environment = environment)
            }.toList()
    }

    data class ApplicationCandidate(
        val name: String,
        val command: List<String>,
        val environment: Map<String, String>,
    )

    companion object {
        private val logger = Logger.getInstance(JetBrainsAcpConfigService::class.java)
    }
}
