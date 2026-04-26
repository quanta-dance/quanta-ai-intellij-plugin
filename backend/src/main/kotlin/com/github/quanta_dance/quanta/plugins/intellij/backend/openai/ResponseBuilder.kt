package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models.OpenAIResponse
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.Instructions
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressEvent
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressKind
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolProgressService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.openai.models.ChatModel
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseTextConfig
import com.openai.models.responses.StructuredResponseCreateParams

class ResponseBuilder(private val project: Project) {
    private fun mergedInstructions(): String {
        val base = Instructions.instructions
        val extra = BackendQuantaSettingsState.instance.settings.extraInstructions?.trim().orEmpty()
        return if (extra.isNotEmpty()) "$base\n\n# User Custom Instructions\n$extra" else base
    }

    fun buildStructuredResponseParams(inputs: List<ResponseInputItem>): StructuredResponseCreateParams<OpenAIResponse> {
        val model = ModelSelector.effectiveModel(ModelSelector.initialModel())

        val format = ResponseTextConfig.builder()
            .verbosity(ResponseTextConfig.Verbosity.HIGH)
            .format(OpenAIResponse::class.java)
            .build()

        val rawParams =
            ResponseCreateParams.builder()
                .instructions(mergedInstructions())
                .inputOfResponse(inputs)
                //.reasoning(TODO)
                //.maxOutputTokens(TODO)
                .model(ChatModel.of(model))
                .text(format)
                .build()
        return rawParams
    }

    fun publishProgress(toolName: String, message: String) {
        project.service<ToolProgressService>().publish(
            ToolProgressEvent(toolName, ToolProgressKind.UPDATE, message),
        )
    }
}
