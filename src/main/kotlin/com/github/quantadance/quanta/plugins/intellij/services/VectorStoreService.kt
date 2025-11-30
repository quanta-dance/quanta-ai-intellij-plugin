// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class VectorStoreService(private val project: Project) {
    private val store: SQLiteVectorStore = SQLiteVectorStore.getInstance(project)

    fun upsert(
        id: String,
        vector: FloatArray,
        metadata: Map<String, String>,
    ) {
        store.upsert(id, vector, metadata)
    }

    fun deleteByProject(projectKey: String) {
        store.deleteByProject(projectKey)
    }

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
