// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.services.openai

import com.intellij.openapi.project.Project

interface ToolInvoker {
    fun invoke(
        project: Project,
        functionCall: com.openai.models.responses.ResponseFunctionToolCall,
    ): Any
}
