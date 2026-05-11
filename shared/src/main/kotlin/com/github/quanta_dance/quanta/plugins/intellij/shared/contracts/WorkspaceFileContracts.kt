/**
 * Shared contracts for workspace file access.
 *
 * This package defines transport-neutral request and result shapes used by both frontend and backend
 * implementations of workspace file operations.
 */
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

/**
 * Shared abstraction for backend-owned workspace file access.
 *
 * Frontend callers should reach file operations through RPC-backed implementations rather than
 * touching the filesystem directly in split-mode.
 */
interface WorkspaceFileService {
    suspend fun read(request: WorkspaceFileReadRequest): WorkspaceFileReadResult

    suspend fun write(request: WorkspaceFileWriteRequest): WorkspaceFileWriteResult
}
