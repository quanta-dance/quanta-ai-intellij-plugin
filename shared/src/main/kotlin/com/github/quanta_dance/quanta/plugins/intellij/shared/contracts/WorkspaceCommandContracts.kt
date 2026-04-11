package com.github.quanta_dance.quanta.plugins.intellij.shared.contracts

data class WorkspaceCommandRequest(
    val command: String,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
)

data class WorkspaceCommandResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

interface WorkspaceCommandService {
    suspend fun execute(request: WorkspaceCommandRequest): WorkspaceCommandResult
}
