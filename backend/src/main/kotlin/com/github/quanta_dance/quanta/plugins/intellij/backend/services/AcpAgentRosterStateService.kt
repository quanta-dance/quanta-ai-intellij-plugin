// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

/**
 * Persists the last verified local ACP application roster for immediate display after an IDE restart.
 *
 * The cached entries are presentation and optimistic-selection data only. A background discovery pass
 * revalidates them before long-running use. Environment overrides are deliberately not persisted
 * because they can contain credentials.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "QuantaAcpAgentRoster",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class AcpAgentRosterStateService : PersistentStateComponent<AcpAgentRosterStateService.State> {
    data class PersistedAgent(
        var id: String = "",
        var name: String = "",
        var command: MutableList<String> = mutableListOf(),
        var executablePath: String = "",
        var version: String? = null,
        var protocolVersion: Int = 0,
    )

    data class State(
        var discoveredAgents: MutableList<PersistedAgent> = mutableListOf(),
    )

    @Volatile
    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun agents(): List<AcpAgentDto> =
        state.discoveredAgents.mapNotNull { agent ->
            agent
                .takeIf { it.id.isNotBlank() && it.name.isNotBlank() && it.executablePath.isNotBlank() }
                ?.let {
                    AcpAgentDto(
                        id = it.id,
                        name = it.name,
                        command = it.command.toList(),
                        executablePath = it.executablePath,
                        version = it.version,
                        protocolVersion = it.protocolVersion,
                    )
                }
        }

    fun replace(agents: List<AcpAgentDto>) {
        state =
            State(
                discoveredAgents =
                    agents
                        .filter { it.peerIdentity == null }
                        .map { agent ->
                            PersistedAgent(
                                id = agent.id,
                                name = agent.name,
                                command = agent.command.toMutableList(),
                                executablePath = agent.executablePath,
                                version = agent.version,
                                protocolVersion = agent.protocolVersion,
                            )
                        }.toMutableList(),
            )
    }
}
