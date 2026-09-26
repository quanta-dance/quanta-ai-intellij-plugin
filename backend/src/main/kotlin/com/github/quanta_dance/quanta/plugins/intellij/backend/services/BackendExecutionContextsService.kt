// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
    private val mcpLifecycleExecutor = namedSinglePool(prefix = "qd-mcp-lifecycle")
    private val interactiveAgentExecutor =
        namedBoundedFixedPool(size = 2, queueCapacity = 32, prefix = "qd-agent-interactive")
    private val backgroundAgentExecutor =
        namedBoundedFixedPool(size = 2, queueCapacity = 32, prefix = "qd-agent-background")
    private val toolExecutionExecutor = namedBoundedFixedPool(size = 4, queueCapacity = 32, prefix = "qd-tool-exec")
    private val acpPeerTaskExecutor = namedBoundedFixedPool(size = 2, queueCapacity = 16, prefix = "qd-acp-peer")
    private val toolCatalogExecutor = namedSinglePool(prefix = "qd-tool-catalog")
    private val chatPublicationExecutor = namedSinglePool(prefix = "qd-chat-pub")
    private val voiceStreamingExecutor = namedSinglePool(prefix = "qd-voice-stream")

    val mcpDispatcher: ExecutorCoroutineDispatcher = mcpExecutor.asCoroutineDispatcher()
    val mcpLifecycleDispatcher: ExecutorCoroutineDispatcher = mcpLifecycleExecutor.asCoroutineDispatcher()
    val interactiveAgentDispatcher: ExecutorCoroutineDispatcher = interactiveAgentExecutor.asCoroutineDispatcher()
    val backgroundAgentDispatcher: ExecutorCoroutineDispatcher = backgroundAgentExecutor.asCoroutineDispatcher()
    val toolExecutionDispatcher: ExecutorCoroutineDispatcher = toolExecutionExecutor.asCoroutineDispatcher()
    val acpPeerTaskDispatcher: ExecutorCoroutineDispatcher = acpPeerTaskExecutor.asCoroutineDispatcher()
    val toolCatalogDispatcher: ExecutorCoroutineDispatcher = toolCatalogExecutor.asCoroutineDispatcher()
    val chatPublicationDispatcher: ExecutorCoroutineDispatcher = chatPublicationExecutor.asCoroutineDispatcher()
    val voiceStreamingDispatcher: ExecutorCoroutineDispatcher = voiceStreamingExecutor.asCoroutineDispatcher()

    val mcpScope: CoroutineScope = CoroutineScope(SupervisorJob() + mcpDispatcher)
    val mcpLifecycleScope: CoroutineScope = CoroutineScope(SupervisorJob() + mcpLifecycleDispatcher)
    val interactiveAgentScope: CoroutineScope = CoroutineScope(SupervisorJob() + interactiveAgentDispatcher)
    val backgroundAgentScope: CoroutineScope = CoroutineScope(SupervisorJob() + backgroundAgentDispatcher)
    val toolExecutionScope: CoroutineScope = CoroutineScope(SupervisorJob() + toolExecutionDispatcher)
    val acpPeerTaskScope: CoroutineScope = CoroutineScope(SupervisorJob() + acpPeerTaskDispatcher)
    val toolCatalogScope: CoroutineScope = CoroutineScope(SupervisorJob() + toolCatalogDispatcher)
    val chatPublicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + chatPublicationDispatcher)
    val voiceStreamingScope: CoroutineScope = CoroutineScope(SupervisorJob() + voiceStreamingDispatcher)

    override fun dispose() {
        listOf(
            mcpScope,
            mcpLifecycleScope,
            interactiveAgentScope,
            backgroundAgentScope,
            toolExecutionScope,
            acpPeerTaskScope,
            toolCatalogScope,
            chatPublicationScope,
            voiceStreamingScope,
        ).forEach { scope ->
            scope.cancel()
        }
        listOf(
            mcpDispatcher,
            mcpLifecycleDispatcher,
            interactiveAgentDispatcher,
            backgroundAgentDispatcher,
            toolExecutionDispatcher,
            acpPeerTaskDispatcher,
            toolCatalogDispatcher,
            chatPublicationDispatcher,
            voiceStreamingDispatcher,
        ).forEach { dispatcher ->
            dispatcher.close()
        }
    }

    private fun namedBoundedFixedPool(
        size: Int,
        queueCapacity: Int,
        prefix: String,
    ): ExecutorService =
        ThreadPoolExecutor(
            size,
            size,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            { runnable -> Thread(runnable, "$prefix-${System.nanoTime()}").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
}
