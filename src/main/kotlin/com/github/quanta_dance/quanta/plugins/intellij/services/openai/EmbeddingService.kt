package com.github.quanta_dance.quanta.plugins.intellij.services.openai

import com.github.quanta_dance.quanta.plugins.intellij.services.SQLiteVectorStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.openai.client.OpenAIClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
class EmbeddingService(private val project: Project) {
    private val log = Logger.getInstance(EmbeddingService::class.java)
    private val oAI: OpenAIClient = OpenAIClientProvider.get(project)

    suspend fun createEmbeddings(texts: List<String>, model: String = "text-embedding-3-small"): List<FloatArray> =
        withContext(Dispatchers.IO) {
            val params = com.openai.models.embeddings.EmbeddingCreateParams.builder()
                .model(com.openai.models.embeddings.EmbeddingModel.of(model))
                .input(texts.first())
                .build()
            val output = oAI.embeddings().create(params).data()
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
