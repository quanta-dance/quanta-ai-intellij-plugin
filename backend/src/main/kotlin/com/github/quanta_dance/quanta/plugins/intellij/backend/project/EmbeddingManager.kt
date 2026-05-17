// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.project

import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.EmbeddingService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.SQLiteVectorStore
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.VectorStoreService
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class EmbeddingManager(
    private val project: com.intellij.openapi.project.Project,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val pending = ConcurrentHashMap<String, Job>()
    private val debounceMillis = 2000L
    private val defaultChunkSize = 800
    private val defaultOverlap = 200

    private val embeddingService: EmbeddingService = project.service()
    private val vectorStore: VectorStoreService = project.service()
    private val sqliteStore: SQLiteVectorStore = SQLiteVectorStore.getInstance(project)
    private val logger = Logger.getInstance(EmbeddingManager::class.java)

    fun enqueueFileForIndexing(
        filePath: String,
        fileText: String,
    ) {
        pending[filePath]?.cancel()
        val job =
            scope.launch {
                delay(debounceMillis)
                try {
                    indexFile(filePath, fileText)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    QDLog.error(logger, { "Indexing failed for $filePath" }, t)
                } finally {
                    pending.remove(filePath)
                }
            }
        pending[filePath] = job
    }

    private suspend fun indexFile(
        filePath: String,
        text: String,
    ) {
        val chunks = slidingWindowChunk(text, defaultChunkSize, defaultOverlap)
        if (chunks.isEmpty()) return
        val projectKey = PathUtils.projectRootPath(project) ?: project.name
        val timestamp = Instant.now().toEpochMilli()
        val ids = mutableListOf<String>()
        val metas = mutableListOf<Map<String, String>>()
        val textsToEmbed = mutableListOf<String>()
        val chunkHashes = mutableListOf<String>()
        for (i in chunks.indices) {
            val chunk = chunks[i]
            val id = "$projectKey|$filePath|$i"
            val hash = sqliteStore.computeHash(chunk)
            val existingHash = sqliteStore.getChunkHash(id)
            if (existingHash != null && existingHash == hash) continue
            ids.add(id)
            metas.add(
                mapOf(
                    "project" to projectKey,
                    "path" to filePath,
                    "chunk" to i.toString(),
                    "timestamp" to timestamp.toString(),
                ),
            )
            textsToEmbed.add(chunk)
            chunkHashes.add(hash)
        }
        if (ids.isEmpty()) return
        embeddingService.createAndStoreEmbeddings(
            ids = ids,
            texts = textsToEmbed,
            metadataList = metas,
            chunkHashes = chunkHashes,
        )
    }

    private fun slidingWindowChunk(
        text: String,
        size: Int,
        overlap: Int,
    ): List<String> = emptyList()
}
