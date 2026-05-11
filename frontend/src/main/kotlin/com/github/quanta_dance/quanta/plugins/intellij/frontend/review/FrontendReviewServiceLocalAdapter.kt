package com.github.quanta_dance.quanta.plugins.intellij.frontend.review

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ReviewRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ReviewResponse
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ReviewService

/**
 * Temporary frontend-local adapter for the shared review contract.
 *
 * TODO: replace this placeholder with the real backend/RPC-backed review execution once the review
 * migration slice is completed.
 */
class FrontendReviewServiceLocalAdapter : ReviewService {
    override suspend fun review(request: ReviewRequest): ReviewResponse =
        ReviewResponse(
            success = false,
            message = "Frontend review action is wired to the shared contract. Backend RPC wiring is the next step.",
        )
}
