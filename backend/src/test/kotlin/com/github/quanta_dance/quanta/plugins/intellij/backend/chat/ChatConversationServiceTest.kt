package com.github.quanta_dance.quanta.plugins.intellij.backend.chat


import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.Instructions
import com.github.quanta_dance.quanta.plugins.intellij.models.OpenAIResponse
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.responses.*
import kotlin.test.Test


class ChatConversationServiceTest {

    @Test
    fun `run test`() {
        val client = OpenAIOkHttpClient
            .builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .baseUrl("https://genai-gateway.agoda.is/v1")
            .build()


        val item = ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .role(EasyInputMessage.Role.SYSTEM)
                .content("Hello")
                .build(),
        )

        val createParams: StructuredResponseCreateParams<OpenAIResponse> = ResponseCreateParams.builder()
            .instructions(Instructions.instructions)
            .inputOfResponse(listOf(item))
            .text(OpenAIResponse::class.java)
            .model(ChatModel.GPT_5_MINI)
            .build()

        client.responses().create(createParams).output().forEach { item ->
            item.message().map { message ->
                message.content().forEach { c ->
                    println(c.asOutputText().summaryMessage)
                }
            }
        }
    }

}