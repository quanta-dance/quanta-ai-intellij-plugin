package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.models.OpenAIResponse
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressEvent
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressKind
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.openai.models.ChatModel
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.StructuredResponseCreateParams

class ResponseBuilder(private val project: Project) {
    private fun mergedInstructions(): String {
        val base = "You are Quanta."
        val extra = BackendQuantaSettingsState.instance.settings.extraInstructions?.trim().orEmpty()
        return if (extra.isNotEmpty()) base + "\n\n# User Custom Instructions\n" + extra else base
    }

    fun buildStructuredResponseParams(inputs: List<ResponseInputItem>): StructuredResponseCreateParams<OpenAIResponse> {
        val rawParams =
            ResponseCreateParams.builder()
                .model(ChatModel.of(ModelSelector.effectiveModel(ModelSelector.initialModel())))
                .instructions(mergedInstructions())
                .inputOfResponse(inputs)
                .build()
        return StructuredResponseCreateParams(OpenAIResponse::class.java, rawParams)
    }

    fun publishProgress(toolName: String, message: String) {
        project.service<ToolProgressService>().publish(
            ToolProgressEvent(toolName, ToolProgressKind.UPDATE, message),
        )
    }
}
