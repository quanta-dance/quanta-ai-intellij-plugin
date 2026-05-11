// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.contracts

data class ReviewRequest(
    val filePath: String? = null,
    val selectedText: String? = null,
    val instruction: String = "Review and suggest changes in selected code",
)

data class ReviewResponse(
    val success: Boolean,
    val message: String,
)

/**
 * Shared contract for code-review style requests.
 *
 * The contract is already consumed by frontend actions, while full backend execution is still part
 * of the modular migration path.
 */
interface ReviewService {
    suspend fun review(request: ReviewRequest): ReviewResponse
}
