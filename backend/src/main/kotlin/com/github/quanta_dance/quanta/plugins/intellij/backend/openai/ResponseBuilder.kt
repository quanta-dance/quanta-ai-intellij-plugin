// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models.OpenAIResponse
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.Instructions
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ToolsRegistry
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp.DynamicMcpToolProvider
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp.McpClientService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressEvent
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressKind
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.openai.core.JsonValue
import com.openai.models.ChatModel
import com.openai.models.responses.*
import java.math.BigDecimal
import java.math.BigInteger

class ResponseBuilder(private val project: Project) {
    private val mapper = jacksonObjectMapper()

    private fun mergedInstructions(): String {
        val base = Instructions.instructions
        val extra = BackendRuntimeSettingsService.instance.settings.extraInstructions?.trim().orEmpty()
        return if (extra.isNotEmpty()) "$base\n\n# User Custom Instructions\n$extra" else base
    }

    private fun builtInToolDescription(toolClass: Class<*>): String =
        toolClass.getAnnotation(JsonClassDescription::class.java)?.value?.takeIf { it.isNotBlank() }
            ?: "Built-in IDE tool: ${toolClass.simpleName}"

    private fun nullableSchema(schema: Map<String, Any>): MutableMap<String, Any> =
        mutableMapOf(
            "anyOf" to listOf(schema, mapOf("type" to "null")),
        )

    private fun schemaForProperty(property: BeanPropertyDefinition): MutableMap<String, Any> {
        val baseSchema = schemaForType(property.primaryType)
        val schema =
            if (property.metadata?.isRequired == true) {
                baseSchema
            } else {
                nullableSchema(baseSchema)
            }
        val description = property.primaryMember?.getAnnotation(JsonPropertyDescription::class.java)?.value
        if (!description.isNullOrBlank()) {
            schema["description"] = description
        }
        return schema
    }

    private fun schemaForType(type: JavaType): MutableMap<String, Any> {
        val rawClass = type.rawClass
        return when {
            rawClass == String::class.java || CharSequence::class.java.isAssignableFrom(rawClass) -> mutableMapOf("type" to "string")
            rawClass == Boolean::class.java || rawClass == java.lang.Boolean.TYPE -> mutableMapOf("type" to "boolean")
            rawClass == Int::class.java || rawClass == Long::class.java ||
                    rawClass == Short::class.java || rawClass == Byte::class.java ||
                    rawClass == java.lang.Integer.TYPE || rawClass == java.lang.Long.TYPE ||
                    rawClass == java.lang.Short.TYPE || rawClass == java.lang.Byte.TYPE ||
                    rawClass == BigInteger::class.java -> mutableMapOf("type" to "integer")

            rawClass == Float::class.java || rawClass == Double::class.java ||
                    rawClass == java.lang.Float.TYPE || rawClass == java.lang.Double.TYPE ||
                    rawClass == BigDecimal::class.java || Number::class.java.isAssignableFrom(rawClass) -> mutableMapOf(
                "type" to "number"
            )

            rawClass.isEnum -> mutableMapOf(
                "type" to "string",
                "enum" to rawClass.enumConstants.map { (it as Enum<*>).name },
            )

            type.isArrayType || type.isCollectionLikeType -> mutableMapOf(
                "type" to "array",
                "items" to schemaForType(type.contentType ?: mapper.constructType(Any::class.java)),
            )

            type.isMapLikeType -> mutableMapOf(
                "type" to "object",
                "additionalProperties" to true,
            )

            rawClass == Any::class.java -> mutableMapOf("type" to "object")
            else -> objectSchema(type)
        }
    }

    private fun objectSchema(type: JavaType): MutableMap<String, Any> {
        val bean = mapper.deserializationConfig.introspect(type)
        val properties = linkedMapOf<String, Any>()

        bean.findProperties()
            .filter { it.couldDeserialize() }
            .forEach { property ->
                properties[property.name] = schemaForProperty(property)
            }

        return linkedMapOf<String, Any>(
            "type" to "object",
            "properties" to properties,
            "required" to properties.keys.toList(),
            "additionalProperties" to false,
        )
    }

    private fun builtInToolParameters(toolClass: Class<*>): FunctionTool.Parameters {
        val schema = objectSchema(mapper.constructType(toolClass))
        return FunctionTool.Parameters.builder()
            .putAdditionalProperty("type", JsonValue.from(schema["type"]))
            .putAdditionalProperty("properties", JsonValue.from(schema["properties"]))
            .putAdditionalProperty("required", JsonValue.from(schema["required"]))
            .putAdditionalProperty("additionalProperties", JsonValue.from(schema["additionalProperties"]))
            .build()
    }

    private fun availableTools(includeMcp: Boolean): List<Tool> {
        val builtInTools: List<Tool> =
            ToolsRegistry.toolsFor(project).map { toolClass ->
                val toolName = toolClass.simpleName
                Tool.ofFunction(
                    FunctionTool.builder()
                        .name(toolName)
                        .description(builtInToolDescription(toolClass))
                        .parameters(builtInToolParameters(toolClass))
                        .strict(true)
                        .build(),
                )
            }
        if (!includeMcp) return builtInTools

        val mcpTools = runCatching {
            DynamicMcpToolProvider.buildTools(project.service<McpClientService>())
        }.getOrDefault(emptyList())
        return builtInTools + mcpTools
    }

    fun buildStructuredResponseParams(
        inputs: List<ResponseInputItem>,
        includeMcp: Boolean = true,
        previousResponseId: String? = null,
    ): StructuredResponseCreateParams<OpenAIResponse> {
        val model = ModelSelector.effectiveModel(ModelSelector.initialModel())

        val format = ResponseTextConfig.builder()
            .verbosity(ResponseTextConfig.Verbosity.HIGH)
            .format(OpenAIResponse::class.java)
            .build()

        val rawParams =
            ResponseCreateParams.builder()
                .instructions(mergedInstructions())
                .previousResponseId(previousResponseId)
                .inputOfResponse(inputs)
                //.reasoning(TODO)
                //.maxOutputTokens(TODO)
                .model(ChatModel.of(model))
                .text(format)
                .tools(availableTools(includeMcp))
                .build()
        return rawParams
    }

    fun publishProgress(toolName: String, message: String) {
        project.service<ToolProgressService>().publish(
            ToolProgressEvent(toolName, ToolProgressKind.UPDATE, message),
        )
    }
}
