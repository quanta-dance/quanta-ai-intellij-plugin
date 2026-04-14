package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ToolsRegistry
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseFunctionToolCall

class DefaultToolInvoker : ToolInvoker {
    private val mapper = jacksonObjectMapper()

    override fun invoke(
        project: Project,
        functionCall: ResponseFunctionToolCall,
    ): Any {
        val toolClass =
            ToolsRegistry.toolsFor(project)
                .firstOrNull { it.simpleName == functionCall.name() || it.name.endsWith(".${functionCall.name()}") }
                ?: error("Unknown tool '${functionCall.name()}'")

        val tool = toolClass.getDeclaredConstructor().newInstance()
        val argsJson = functionCall.arguments()
        val argsMap = runCatching { mapper.readValue(argsJson, Map::class.java) }.getOrDefault(emptyMap<String, Any?>())
        argsMap.forEach { (key, value) ->
            try {
                val field = toolClass.getDeclaredField(key.toString())
                field.isAccessible = true
                field.set(tool, value)
            } catch (_: Throwable) {
            }
        }
        return (tool as com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface<Any?>).execute(
            project
        ) as Any
    }
}
