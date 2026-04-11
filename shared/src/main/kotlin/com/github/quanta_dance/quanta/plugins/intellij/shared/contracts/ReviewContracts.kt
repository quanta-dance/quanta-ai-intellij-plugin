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

interface ReviewService {
    suspend fun review(request: ReviewRequest): ReviewResponse
}
