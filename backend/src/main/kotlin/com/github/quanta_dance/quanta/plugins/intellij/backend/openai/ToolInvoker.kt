// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall

interface ToolInvoker {
    fun invoke(
        project: Project,
        functionCall: ResponseFunctionToolCall,
    ): Any
}
