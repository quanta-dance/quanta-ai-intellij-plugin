package com.github.quanta_dance.quanta.plugins.intellij.backend.repository

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.Instructions
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.ResponsesModel
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem

class OpenAIBackendChatResponder(
    private var apiKey: String = System.getenv("OPENAI_API_KEY") ?: "",
    private var model: String = System.getenv("OPENAI_MODEL") ?: "gpt-4o-mini",
    private var baseUrl: String = System.getenv("OPENAI_BASE_URL") ?: "https://api.openai.com/v1",
) {
    fun generateResponse(
        messages: List<ChatTurn>,
        systemInstructions: String = mergedInstructions(),
        contextMessage: String? = null,
    ): String {
        val settings = BackendQuantaSettingsState.instance.settings
        apiKey = settings.openAiToken
        model = settings.model
        baseUrl = settings.openAiUrl

        if (apiKey.isBlank()) {
            return "OpenAI is not configured on the backend yet. Set OPENAI_API_KEY to enable real responses."
        }

        try {
            val client =
                OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .maxRetries(2)
                    .build()

            val params = buildRequest(messages, systemInstructions, contextMessage)
            val text = StringBuilder()
            client.responses().createStreaming(params).use { streamResponse ->
                for (event in streamResponse.stream()) {
                    event.outputTextDelta().ifPresent { delta -> text.append(delta.delta()) }
                }
            }
            return text.toString().trim().takeIf { it.isNotBlank() } ?: "I couldn't extract a response from OpenAI."
        } catch (e: Throwable) {
            println(messages.joinToString("\n"))
        }
        return ""
    }

    private fun mergedInstructions(): String {
        val base = Instructions.instructions
        val extra = BackendQuantaSettingsState.instance.settings.extraInstructions?.trim().orEmpty()
        return if (extra.isNotEmpty()) base + "\n\n# User Custom Instructions\n" + extra else base
    }

    private fun buildRequest(
        messages: List<ChatTurn>,
        systemInstructions: String,
        contextMessage: String?
    ): ResponseCreateParams {
        val input = buildList<ResponseInputItem> {
            add(
                ResponseInputItem.ofEasyInputMessage(
                    EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.SYSTEM)
                        .content(systemInstructions)
                        .build(),
                ),
            )
            if (!contextMessage.isNullOrBlank()) {
                add(
                    ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage.builder()
                            .role(EasyInputMessage.Role.SYSTEM)
                            .content(contextMessage)
                            .build(),
                    ),
                )
            }
            messages.forEach { turn ->
                add(
                    ResponseInputItem.ofEasyInputMessage(
                        EasyInputMessage.builder()
                            .role(
                                when (turn.role.lowercase()) {
                                    "assistant" -> EasyInputMessage.Role.ASSISTANT
                                    else -> EasyInputMessage.Role.USER
                                },
                            )
                            .content(turn.content)
                            .build(),
                    ),
                )
            }
        }

        return ResponseCreateParams.builder()
            .model(ResponsesModel.ofChat(ChatModel.of(model)))
            .inputOfResponse(input)
            .build()
    }

    data class ChatTurn(
        val role: String,
        val content: String,
    )
}
