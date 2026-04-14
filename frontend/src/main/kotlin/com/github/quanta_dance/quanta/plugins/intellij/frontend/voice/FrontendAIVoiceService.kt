// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.voice

import com.github.quanta_dance.quanta.plugins.intellij.frontend.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.frontend.openai.FrontendOpenAIClientProvider
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.sound.Player
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
class FrontendAIVoiceService(private val project: Project) {
    private var process: Process? = null

    companion object {
        private val logger = Logger.getInstance(FrontendAIVoiceService::class.java)
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

    }

    fun say(message: String) {
        if (!FrontendQuantaSettingsState.instance.state.voiceEnabled) return
        stopTalking()
        QDLog.debug(logger) { "Muting mic while speaking" }

        val useLocalMacTts =
            System.getProperty("os.name").contains("Mac", ignoreCase = true) &&
                    FrontendQuantaSettingsState.instance.state.voiceByLocalTTS
        if (useLocalMacTts) {
            val th =
                Thread {
                    try {
                        process = ProcessBuilder("say", message).inheritIO().start()
                        process?.waitFor()
                    } catch (e: Exception) {
                        QDLog.error(logger, { "Local TTS process failed" }, e)
                    } finally {

                    }
                }
            th.isDaemon = true
            th.start()
            return
        }

        val client = FrontendOpenAIClientProvider.get(project)
        val promise = CompletableFuture<Unit>()
        val params =
            SpeechCreateParams.builder()
                .input(message)
                .model(SpeechModel.GPT_4O_MINI_TTS)
                .voice(SpeechCreateParams.Voice.UnionMember1.ASH)
                .responseFormat(SpeechCreateParams.ResponseFormat.MP3)
                .build()
        client.async().audio().speech().create(params).thenAcceptAsync { response ->
            try {
                response.body().let { inputStream ->
                    if (inputStream == null) throw IllegalStateException("No MP3 response stream")
                    BufferedInputStream(inputStream).use { stream ->
                        Player.playMp3(stream)
                    }
                }
            } catch (e: Exception) {
                QDLog.error(logger, { "Speech synthesis/playback failed" }, e)
            } finally {
                promise.complete(Unit)

            }
        }
        promise.join()
    }
}
