// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.contracts

import kotlinx.serialization.Serializable

@Serializable
data class ToolExecutionItem(
    val callId: String,
    val toolName: String,
    val displayText: String,
    val status: ToolExecutionStatus,
    /** Project-relative file path that can be opened in an editor. */
    val filePath: String? = null,
    /** Project-relative directory path that can be selected in the Project tool window. */
    val directoryPath: String? = null,
    val errorText: String? = null,
    val detailText: String? = null,
)

@Serializable
enum class ToolExecutionStatus {
    EXECUTING,
    SUCCEEDED,
    FAILED,
}
