// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.comment

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CommentService

object FrontendCommentServices {
    fun commentService(): CommentService = FrontendCommentServiceLocalAdapter()
}
