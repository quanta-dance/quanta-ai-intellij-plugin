package com.github.quanta_dance.quanta.plugins.intellij.backend.contracts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CommentRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CommentResponse
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CommentService

class BackendCommentServicePlaceholder : CommentService {
    override suspend fun comment(request: CommentRequest): CommentResponse =
        CommentResponse(
            success = false,
            message = "Backend comment service is not wired yet.",
        )
}
