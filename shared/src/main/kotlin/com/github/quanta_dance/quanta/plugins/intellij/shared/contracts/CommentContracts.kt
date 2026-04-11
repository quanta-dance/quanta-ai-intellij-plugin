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

interface CommentService {
    suspend fun comment(request: CommentRequest): CommentResponse
}
