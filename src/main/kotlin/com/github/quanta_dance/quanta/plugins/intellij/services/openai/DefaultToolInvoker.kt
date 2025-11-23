package com.github.quanta_dance.quanta.plugins.intellij.services.openai

import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolsRegistry
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall

class DefaultToolInvoker : ToolInvoker {
    private val log = Logger.getInstance(DefaultToolInvoker::class.java)

    override fun invoke(project: Project, functionCall: ResponseFunctionToolCall): Any {
        val name = functionCall.name()
        val available = ToolsRegistry.toolsFor(project)
        available.forEach { toolClass ->
            if (toolClass.simpleName == name) {
                try {
                    val args = functionCall.arguments(toolClass)
                    return args.execute(project)
                } catch (e: Throwable) {
                    log.error("Tool '$name' failed: ${e.message}", e)
                    throw e
                }
            }
        }
        log.warn("Unknown function tool requested: $name")
        throw IllegalArgumentException("Unknown function: $name")
    }
}
