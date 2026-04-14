package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall

interface ToolInvoker {
    fun invoke(
        project: Project,
        functionCall: ResponseFunctionToolCall,
    ): Any
}
