// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.project

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.quanta_dance.quanta.plugins.intellij.services.openai.EmbeddingService
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.github.quanta_dance.quanta.plugins.intellij.tools.models.UpsertEmbeddingResult
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking

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

        // Generate embedding and store locally
        runBlocking {
            embeddingService.createAndStoreEmbeddings(listOf(pid), listOf(txt), listOf(md))
        }
        return UpsertEmbeddingResult(pid, "ok")
    }
}
