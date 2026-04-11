// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class EmbeddingService(@Suppress("unused") private val project: Project) {
    suspend fun createAndStoreEmbeddings(
        ids: List<String>,
        texts: List<String>,
        metadataList: List<Map<String, String>>? = null,
        retryCount: Int = 3,
        retryDelayMillis: Long = 1000L,
        model: String = "text-embedding-3-small",
        chunkHashes: List<String>? = null,
    ) {
    }
}
