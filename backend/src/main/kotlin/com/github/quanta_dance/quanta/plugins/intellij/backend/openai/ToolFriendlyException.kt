package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

class ToolFriendlyException(
    message: String,
    val code: String = "tool_error",
    val retriable: Boolean = false,
) : RuntimeException(message)
