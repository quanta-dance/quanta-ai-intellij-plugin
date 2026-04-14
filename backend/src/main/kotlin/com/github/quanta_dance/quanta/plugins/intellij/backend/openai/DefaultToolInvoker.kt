package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall

class DefaultToolInvoker : ToolInvoker {
    override fun invoke(
        project: Project,
        functionCall: ResponseFunctionToolCall,
    ): Any {
        error("Built-in tool invocation has not been migrated yet")
    }
}
