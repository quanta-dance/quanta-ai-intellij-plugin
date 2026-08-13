// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.contracts

data class CommentRequest(
    val filePath: String? = null,
    val selectedText: String? = null,
    val instruction: String = "Add comments to selected code.",
)

data class CommentResponse(
    val success: Boolean,
    val message: String,
)

/**
 * Shared contract for comment/documentation assistance requests.
 *
 * The contract already gives frontend features a stable API, while backend-owned implementation is
 * still being migrated into the split architecture.
 */
interface CommentService {
    suspend fun comment(request: CommentRequest): CommentResponse
}
