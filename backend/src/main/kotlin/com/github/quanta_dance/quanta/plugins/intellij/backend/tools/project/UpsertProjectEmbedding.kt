// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.project

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.EmbeddingService
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.models.UpsertEmbeddingResult
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking

/**
 * Tool that turns a single text chunk into an embedding and stores it in the local vector DB.
 *
 * The tool is intentionally lightweight: it accepts the embedding id, raw text, and optional
 * metadata, then delegates the actual embedding generation/storage to [EmbeddingService].
 */
@JsonClassDescription("Create or update embedding for a given id and text in project-local vector DB.")
class UpsertProjectEmbedding : ToolInterface<UpsertEmbeddingResult> {
    @field:JsonPropertyDescription("Unique id for the embedding (e.g., project|path|chunkIndex)")
    var id: String? = null

    @field:JsonPropertyDescription("Text content to embed.")
    var text: String? = null

    @field:JsonPropertyDescription("Optional metadata as JSON string.")
    var metadataJson: String? = null

    override fun execute(project: Project): UpsertEmbeddingResult {
        val embeddingService = project.service<EmbeddingService>()
        val pid = id ?: throw IllegalArgumentException("id required")
        val txt = text ?: ""

        val md: Map<String, String> =
            if (metadataJson.isNullOrBlank()) {
                emptyMap()
            } else {
                try {
                    val mapper = jacksonObjectMapper()
                    mapper.readValue(metadataJson!!)
                } catch (e: Exception) {
                    emptyMap()
                }
            }

        // Keep the tool synchronous from the caller's perspective while the suspend work runs.
        runBlocking {
            embeddingService.createAndStoreEmbeddings(listOf(pid), listOf(txt), listOf(md))
        }
        return UpsertEmbeddingResult(pid, "ok")
    }
}
