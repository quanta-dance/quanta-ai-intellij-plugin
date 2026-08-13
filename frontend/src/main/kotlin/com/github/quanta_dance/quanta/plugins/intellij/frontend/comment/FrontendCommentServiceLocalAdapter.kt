// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.comment

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CommentRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CommentResponse
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CommentService

/**
 * Temporary frontend-local adapter for the shared comment contract.
 *
 * TODO: replace this placeholder with the real backend/RPC-backed comment execution once the
 * modular migration for comment flows is completed.
 */
class FrontendCommentServiceLocalAdapter : CommentService {
    override suspend fun comment(request: CommentRequest): CommentResponse =
        CommentResponse(
            success = false,
            message = "Comment action is wired to the shared contract. Backend comment execution is the next migration step.",
        )
}
