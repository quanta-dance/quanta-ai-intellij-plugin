package com.github.quanta_dance.quanta.plugins.intellij.services.openai

import com.intellij.openapi.project.Project

interface ToolInvoker {
    fun invoke(project: Project, functionCall: com.openai.models.responses.ResponseFunctionToolCall): Any
}
