// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.review

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ReviewService

object FrontendReviewServices {
    fun reviewService(): ReviewService = FrontendReviewServiceLocalAdapter()
}
