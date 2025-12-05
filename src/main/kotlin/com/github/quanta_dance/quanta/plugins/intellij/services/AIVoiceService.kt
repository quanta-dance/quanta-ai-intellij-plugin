// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.services.openai.OpenAIClientProvider
import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.github.quanta_dance.quanta.plugins.intellij.sound.Player
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.openai.core.MultipartField
import com.openai.models.audio.AudioModel
import com.openai.models.audio.speech.SpeechCreateParams
import com.openai.models.audio.speech.SpeechModel
import com.openai.models.audio.transcriptions.TranscriptionCreateParams
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture

@Service(Service.Level.PROJECT)
class AIVoiceService(private val project: Project) {
    private var process: Process? = null

    companion object {
        private val logger = Logger.getInstance(AIVoiceService::class.java)
    }

    fun stopTalking() {
        try {
            QDLog.debug(logger) { "Stopping voice service..." }
            process?.destroy()
        } catch (_: Throwable) {
        }
        try {
            Player.stop()
        } catch (_: Throwable) {
        }
        project.service<QuantaAIService>().mute(false)
    }

    fun say(message: String) {
        if (!QuantaAISettingsState.instance.state.voiceEnabled) return
        // Stop any ongoing speech (local process or mp3 playback) before starting new
        stopTalking()
        QDLog.debug(logger) { "Muting mic while speaking" }
        project.service<QuantaAIService>().mute(true)
        val useLocalMacTts =
            System.getProperty("os.name").contains("Mac", ignoreCase = true) &&
                QuantaAISettingsState.instance.state.voiceByLocalTTS
        if (useLocalMacTts) {
            val th =
                Thread {
                    try {
                        process = ProcessBuilder("say", message).inheritIO().start()
                        process?.waitFor()
                    } catch (e: Exception) {
                        QDLog.error(logger, { "Local TTS process failed" }, e)
                    } finally {
                        project.service<QuantaAIService>().mute(false)
                    }
                }
            th.isDaemon = true
            th.start()
        } else {
            // Use OpenAI speech (async) via shared client
            speech(message) { mp3Stream ->
                Player.playMp3(mp3Stream) {
                    project.service<QuantaAIService>().mute(false)
                }
            }.whenComplete { _, ex ->
                if (ex != null) {
                    QDLog.error(logger, { "Speech synthesis/playback failed" }, ex)
                    project.service<QuantaAIService>().mute(false)
                }
            }
        }
    }

    // TTS
    fun speech(message: String, consumer: (InputStream) -> Unit): CompletableFuture<Void> {
        val client = OpenAIClientProvider.get(project)
        val params = SpeechCreateParams.builder()
            .input(message)
            .model(SpeechModel.GPT_4O_MINI_TTS)
            .voice(SpeechCreateParams.Voice.ASH)
            .responseFormat(SpeechCreateParams.ResponseFormat.MP3)
            .build()
        return client.async().audio().speech().create(params).thenAcceptAsync { response ->
            val inp = BufferedInputStream(response.body())
            consumer(inp)
        }
    }

    // ASR: synchronous helper
    fun transcript(inputStream: InputStream): String {
        return try { transcriptAsync(inputStream).get() } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw RuntimeException("Transcription was interrupted", e)
        } catch (e: Exception) { throw RuntimeException("Failed to transcribe audio", e) }
    }

    // ASR: async complete result
    fun transcriptAsync(inputStream: InputStream): CompletableFuture<String> {
        val client = OpenAIClientProvider.get(project)
        val mf = MultipartField.builder<InputStream>()
            .value(inputStream)
            .contentType("audio/wav")
            .filename("audio.wav")
            .build()
        val params = TranscriptionCreateParams.builder()
            .file(mf)
            .model(AudioModel.WHISPER_1)
            .build()
        return client.async().audio().transcriptions().create(params)
            .thenApply { response -> response.asTranscription().text() }
    }

    // ASR: streaming deltas
    fun transcriptStreaming(
        inputStream: InputStream,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
    ): CompletableFuture<Void?> {
        val client = OpenAIClientProvider.get(project)
        val mf = MultipartField.builder<InputStream>()
            .value(inputStream)
            .contentType("audio/wav")
            .filename("audio.wav")
            .build()
        val params = TranscriptionCreateParams.builder().file(mf).model(AudioModel.WHISPER_1).build()
        val response = client.async().audio().transcriptions().createStreaming(params)
        response.subscribe { event ->
            if (event.isTranscriptTextDelta()) onDelta(event.asTranscriptTextDelta().delta())
            else if (event.isTranscriptTextDone()) onDone(event.asTranscriptTextDone().text())
        }
        return response.onCompleteFuture()
    }
}
