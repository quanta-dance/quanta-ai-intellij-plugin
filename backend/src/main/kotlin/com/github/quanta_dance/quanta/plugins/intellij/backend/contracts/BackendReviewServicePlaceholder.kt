// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.contracts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ReviewRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ReviewResponse
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ReviewService

class BackendReviewServicePlaceholder : ReviewService {
    override suspend fun review(request: ReviewRequest): ReviewResponse =
        ReviewResponse(
            success = false,
            message = "Backend review service is not wired yet.",
        )
}
