// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendQuantaSettingsState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
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
        project.service<QuantaAIService>().mute(false)
    }

    fun say(message: String) {
        if (!BackendQuantaSettingsState.instance.state.voiceEnabled) return
        // Stop any ongoing speech (local process or mp3 playback) before starting new
        stopTalking()
        QDLog.debug(logger) { "Muting mic while speaking" }
        project.service<QuantaAIService>().mute(true)
        val useLocalMacTts =
            System.getProperty("os.name").contains("Mac", ignoreCase = true) &&
                    BackendQuantaSettingsState.instance.state.voiceByLocalTTS
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
                project.service<QuantaAIService>().mute(false)
            }.whenComplete { _, ex ->
                if (ex != null) {
                    QDLog.error(logger, { "Speech synthesis/playback failed" }, ex)
                    project.service<QuantaAIService>().mute(false)
                }
            }
        }
    }

    // TTS
    fun speech(
        message: String,
        consumer: (InputStream) -> Unit,
    ): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

    // ASR: synchronous helper
    fun transcript(inputStream: InputStream): String {
        return try {
            transcriptAsync(inputStream).get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeException("Transcription was interrupted", e)
        } catch (e: Exception) {
            throw RuntimeException("Failed to transcribe audio", e)
        }
    }

    // ASR: async complete result
    fun transcriptAsync(inputStream: InputStream): CompletableFuture<String> =
        CompletableFuture.completedFuture("")

    // ASR: streaming deltas
    fun transcriptStreaming(
        inputStream: InputStream,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
    ): CompletableFuture<Void?> =
        CompletableFuture.completedFuture(null)
}
