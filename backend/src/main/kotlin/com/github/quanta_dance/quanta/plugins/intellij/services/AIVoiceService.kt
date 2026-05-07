package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.OpenAIClientProvider
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.openai.models.audio.speech.SpeechCreateParams
import com.openai.models.audio.speech.SpeechModel

@Service(Service.Level.PROJECT)
class AIVoiceService(private val project: Project) {
    companion object {
        private val logger = Logger.getInstance(AIVoiceService::class.java)
    }

    fun say(message: String): ByteArray {
        val text = message.trim()
        if (text.isEmpty()) return ByteArray(0)
        QDLog.info(logger) { "AIVoiceService.say: synthesizing speech for ${text.take(80)}" }

        val params =
            SpeechCreateParams.builder()
                .input(text)
                .model(SpeechModel.GPT_4O_MINI_TTS)
                .voice(SpeechCreateParams.Voice.UnionMember1.ASH)
                .responseFormat(SpeechCreateParams.ResponseFormat.MP3)
                .build()

        OpenAIClientProvider.get(project).audio().speech().create(params).use { response ->
            val bytes = response.body().readBytes()
            QDLog.info(logger) { "AIVoiceService.say: synthesized ${bytes.size} bytes" }
            return bytes
        }
    }

    fun stopTalking() = Unit
}
