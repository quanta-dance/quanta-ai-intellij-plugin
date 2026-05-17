// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.github.quanta_dance.quanta.plugins.intellij.backend.services.SQLiteVectorStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.openai.client.OpenAIClient
import com.openai.models.embeddings.EmbeddingCreateParams
import com.openai.models.embeddings.EmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
class EmbeddingService(
    private val project: Project,
) {
    private val log = Logger.getInstance(EmbeddingService::class.java)

    private fun client(): OpenAIClient = OpenAIClientProvider.get(project)

    suspend fun createEmbeddings(
        texts: List<String>,
        model: String = "text-embedding-3-small",
    ): List<FloatArray> =
        withContext(Dispatchers.IO) {
            val params =
                EmbeddingCreateParams
                    .builder()
                    .model(EmbeddingModel.of(model))
                    .input(texts.first())
                    .build()
            val output = client().embeddings().create(params).data()
            output.map { item -> item.embedding().map { it.toFloat() }.toFloatArray() }
        }

    suspend fun createAndStoreEmbeddings(
        ids: List<String>,
        texts: List<String>,
        metadataList: List<Map<String, String>>? = null,
        retryCount: Int = 3,
        retryDelayMillis: Long = 1000L,
        model: String = "text-embedding-3-small",
        chunkHashes: List<String>? = null,
    ) {
        require(ids.size == texts.size) { "ids and texts must have same size" }
        if (chunkHashes != null) require(chunkHashes.size == ids.size) { "chunkHashes must match ids size" }
        val mdList = metadataList ?: List(texts.size) { emptyMap<String, String>() }
        var attempts = 0
        while (true) {
            try {
                val embeddings = createEmbeddings(texts, model)
                val store = project.service<SQLiteVectorStore>()
                for (i in embeddings.indices) {
                    val ch = chunkHashes?.get(i)
                    store.upsert(ids[i], embeddings[i], mdList[i], ch)
                }
                return
            } catch (e: Exception) {
                attempts++
                if (attempts >= retryCount) throw e
                log.warn("Embedding generation/storage failed, retrying... attempt=$attempts", e)
                delay(retryDelayMillis * attempts)
            }
        }
    }
}
