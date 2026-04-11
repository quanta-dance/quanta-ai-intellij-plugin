package com.github.quanta_dance.quanta.plugins.intellij.frontend.review

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ReviewService

object FrontendReviewServices {
    fun reviewService(): ReviewService = FrontendReviewServiceLocalAdapter()
}
