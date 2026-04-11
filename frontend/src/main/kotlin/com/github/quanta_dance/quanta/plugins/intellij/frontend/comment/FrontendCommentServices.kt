package com.github.quanta_dance.quanta.plugins.intellij.frontend.comment

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CommentService

object FrontendCommentServices {
    fun commentService(): CommentService = FrontendCommentServiceLocalAdapter()
}
