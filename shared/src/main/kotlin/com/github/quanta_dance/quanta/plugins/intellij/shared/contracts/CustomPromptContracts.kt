// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.contracts

data class CustomPromptRequest(
    val prompt: String,
)

data class CustomPromptResponse(
    val success: Boolean,
    val message: String,
)

/**
 * Shared contract for custom user-authored prompt execution.
 *
 * TODO: backend-owned execution should become the source of truth once the custom-prompt migration
 * slice is completed.
 */
interface CustomPromptService {
    suspend fun run(request: CustomPromptRequest): CustomPromptResponse
}
