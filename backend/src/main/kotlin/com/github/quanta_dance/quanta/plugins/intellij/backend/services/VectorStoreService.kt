// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
/**
 * Project-scoped facade over the backing vector store.
 *
 * Keeps callers decoupled from the concrete SQLite implementation and exposes only the
 * minimal operations needed by the plugin services and tools.
 */
class VectorStoreService(private val project: Project) {
    private val store: SQLiteVectorStore = SQLiteVectorStore.getInstance(project)

    /** Inserts or updates a vector along with its metadata. */
    fun upsert(
        id: String,
        vector: FloatArray,
        metadata: Map<String, String>,
    ) {
        store.upsert(id, vector, metadata)
    }

    /** Removes all vectors associated with a project key. */
    fun deleteByProject(projectKey: String) {
        store.deleteByProject(projectKey)
    }

    /**
     * Searches for the nearest vectors to [queryVector].
     *
     * @param topK maximum number of results to return
     * @param projectKey optional filter limiting results to one project
     */
    fun search(
        queryVector: FloatArray,
        topK: Int = 10,
        projectKey: String? = null,
    ): List<SearchResult> {
        return store.search(queryVector, topK, projectKey)
    }

    companion object {
        fun getInstance(project: Project): VectorStoreService = project.getService(VectorStoreService::class.java)
    }
}
