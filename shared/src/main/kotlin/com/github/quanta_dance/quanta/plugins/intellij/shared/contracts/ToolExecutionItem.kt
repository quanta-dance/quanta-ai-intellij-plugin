package com.github.quanta_dance.quanta.plugins.intellij.shared.contracts

import kotlinx.serialization.Serializable

@Serializable
data class ToolExecutionItem(
    val callId: String,
    val toolName: String,
    val displayText: String,
    val status: ToolExecutionStatus,
    val filePath: String? = null,
    val errorText: String? = null,
)

@Serializable
enum class ToolExecutionStatus {
    EXECUTING,
    SUCCEEDED,
    FAILED,
}
