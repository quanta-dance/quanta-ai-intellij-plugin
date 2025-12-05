// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.project

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.VectorStoreService
import com.github.quanta_dance.quanta.plugins.intellij.services.openai.EmbeddingService
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.github.quanta_dance.quanta.plugins.intellij.tools.models.SearchProjectEmbeddingsResult
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking

@JsonClassDescription("Search project embeddings for a query text. Returns topK results with id, score and metadata.")
class SearchProjectEmbeddings : ToolInterface<SearchProjectEmbeddingsResult> {
    @field:JsonPropertyDescription("The query text to search for.")
    var queryText: String? = null

    @field:JsonPropertyDescription("Number of results to return.")
    var topK: Int = 5

    override fun execute(project: Project): SearchProjectEmbeddingsResult {
        val query = queryText ?: ""
        val embeddingService = project.service<EmbeddingService>()
        val vectorStore = project.service<VectorStoreService>()

        // Generate embedding synchronously via EmbeddingService (suspend -> runBlocking)
        val embedding =
            runBlocking {
                embeddingService.createEmbeddings(listOf(query))[0]
            }
        val results = vectorStore.search(embedding, topK, project.basePath ?: project.name)

        val items =
            results.map { r ->
                SearchProjectEmbeddingsResult.Item(r.id, r.score, r.metadata, r.metadata["path"].orEmpty())
            }
        return SearchProjectEmbeddingsResult(items)
    }
}
