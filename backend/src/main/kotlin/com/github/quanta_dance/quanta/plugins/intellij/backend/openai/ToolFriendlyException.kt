// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

class ToolFriendlyException(
    message: String,
    val code: String = "tool_error",
    val retriable: Boolean = false,
) : RuntimeException(message)
