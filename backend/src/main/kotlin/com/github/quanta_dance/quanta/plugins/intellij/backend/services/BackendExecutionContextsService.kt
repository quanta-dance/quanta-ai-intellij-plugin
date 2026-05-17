// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns isolated execution contexts for backend subsystems that should not contend with each other.
 *
 * The main goal is to keep MCP server lifecycle/tool execution, agent orchestration, chat publication,
 * and voice streaming on separate dispatchers so blocking or long-running work in one subsystem does
 * not starve the others.
 */
@Service(Service.Level.PROJECT)
class BackendExecutionContextsService : Disposable {
    private fun namedFixedPool(
        size: Int,
        prefix: String,
    ): ExecutorService =
        Executors.newFixedThreadPool(size) { runnable ->
            Thread(runnable, "$prefix-${System.nanoTime()}").apply { isDaemon = true }
        }

    private fun namedSinglePool(prefix: String): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "$prefix-${System.nanoTime()}").apply { isDaemon = true }
        }

    private val mcpExecutor = namedFixedPool(size = 4, prefix = "qd-mcp")
    private val agentOrchestrationExecutor = namedFixedPool(size = 4, prefix = "qd-agent-orch")
    private val chatPublicationExecutor = namedSinglePool(prefix = "qd-chat-pub")
    private val voiceStreamingExecutor = namedSinglePool(prefix = "qd-voice-stream")

    val mcpDispatcher: ExecutorCoroutineDispatcher = mcpExecutor.asCoroutineDispatcher()
    val agentOrchestrationDispatcher: ExecutorCoroutineDispatcher = agentOrchestrationExecutor.asCoroutineDispatcher()
    val chatPublicationDispatcher: ExecutorCoroutineDispatcher = chatPublicationExecutor.asCoroutineDispatcher()
    val voiceStreamingDispatcher: ExecutorCoroutineDispatcher = voiceStreamingExecutor.asCoroutineDispatcher()

    val mcpScope: CoroutineScope = CoroutineScope(SupervisorJob() + mcpDispatcher)
    val agentOrchestrationScope: CoroutineScope = CoroutineScope(SupervisorJob() + agentOrchestrationDispatcher)
    val chatPublicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + chatPublicationDispatcher)
    val voiceStreamingScope: CoroutineScope = CoroutineScope(SupervisorJob() + voiceStreamingDispatcher)

    override fun dispose() {
        listOf(
            mcpDispatcher,
            agentOrchestrationDispatcher,
            chatPublicationDispatcher,
            voiceStreamingDispatcher,
        ).forEach { dispatcher ->
            (dispatcher as? ExecutorCoroutineDispatcher)?.close()
        }
    }
}
