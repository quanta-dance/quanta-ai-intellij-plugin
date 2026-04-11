package com.github.quanta_dance.quanta.plugins.intellij.shared.contracts

data class CustomPromptRequest(
    val prompt: String,
)

data class CustomPromptResponse(
    val success: Boolean,
    val message: String,
)

interface CustomPromptService {
    suspend fun run(request: CustomPromptRequest): CustomPromptResponse
}
