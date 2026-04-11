package com.github.quanta_dance.quanta.plugins.intellij.shared.contracts

data class WorkspaceFileReadRequest(
    val path: String,
)

data class WorkspaceFileReadResult(
    val success: Boolean,
    val content: String,
    val error: String? = null,
    val source: String = "unknown",
)

data class WorkspaceFileWriteRequest(
    val path: String,
    val content: String,
)

data class WorkspaceFileWriteResult(
    val success: Boolean,
    val error: String? = null,
    val source: String = "unknown",
)

interface WorkspaceFileService {
    suspend fun read(request: WorkspaceFileReadRequest): WorkspaceFileReadResult

    suspend fun write(request: WorkspaceFileWriteRequest): WorkspaceFileWriteResult
}
