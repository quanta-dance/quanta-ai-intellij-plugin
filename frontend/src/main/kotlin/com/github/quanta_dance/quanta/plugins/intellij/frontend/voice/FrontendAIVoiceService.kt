// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.voice

import com.github.quanta_dance.quanta.plugins.intellij.frontend.openai.FrontendOpenAIClientProvider
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.sound.Player
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.openai.core.MultipartField
import com.openai.models.audio.AudioModel
import com.openai.models.audio.speech.SpeechCreateParams
import com.openai.models.audio.speech.SpeechModel
import com.openai.models.audio.transcriptions.TranscriptionCreateParams
import javazoom.jl.player.Player as JLayerPlayer
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
                        logger.warn("Local TTS process failed", e)
                    }
                }
            th.isDaemon = true
            th.start()
        } else {
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating Speech...") {
                override fun run(indicator: ProgressIndicator) {
                    // This runs on a background thread automatically
                    speech(message) { mp3Stream ->
                        // Audio playback is a blocking IO operation, perfect for here
                        Player.playMp3(mp3Stream)
                    }.join() // Use .join() to wait for the CompletableFuture inside the background task
                }
            })
        }
    }

    // TTS
    fun speech(
        message: String,
        consumer: (InputStream) -> Unit,
    ): CompletableFuture<Void> {
        try {
            val player = JLayerPlayer(null)

        } catch (e: Throwable) {
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup("Plugin Notifications")
                .createNotification(
                    "Quanta AI",
                    e.cause.toString(), // response.toString() might be too long/unfriendly
                    NotificationType.INFORMATION,
                ).notify(project)
        }


        val client = FrontendOpenAIClientProvider.get(project)

        NotificationGroupManager
            .getInstance()
            .getNotificationGroup("Plugin Notifications")
            .createNotification(
                "Quanta AI",
                "Audio generated ", // response.toString() might be too long/unfriendly
                NotificationType.INFORMATION,
            ).notify(project)

        val params =
            SpeechCreateParams.builder()
                .input(message)
                .model(SpeechModel.GPT_4O_MINI_TTS)
                .voice(SpeechCreateParams.Voice.UnionMember1.ASH)
                .responseFormat(SpeechCreateParams.ResponseFormat.MP3)
                .build()

      //  ApplicationManager.getApplication().invokeLater({

      //  }, ModalityState.any())

//         val inp = BufferedInputStream(client.audio().speech().create(params).body())
//        consumer(inp)
//
//        return CompletableFuture.completedFuture(null)
        return client.async().audio().speech().create(params).thenAcceptAsync { response ->
            // 1. Move to EDT for the Notification
            ApplicationManager.getApplication().invokeLater({
                NotificationGroupManager
                    .getInstance()
                    .getNotificationGroup("Plugin Notifications")
                    .createNotification(
                        "Quanta AI",
                        "Audio generated successfully", // response.toString() might be too long/unfriendly
                        NotificationType.INFORMATION,
                    ).notify(project)
            }, ModalityState.any())

            // 2. Process the InputStream
            val inp = BufferedInputStream(response.body())
            consumer(inp)
        }
    }

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
    fun transcriptAsync(inputStream: InputStream): CompletableFuture<String> {
        val client = FrontendOpenAIClientProvider.get(project)
        val mf =
            MultipartField.builder<InputStream>()
                .value(inputStream)
                .contentType("audio/wav")
                .filename("audio.wav")
                .build()
        val params =
            TranscriptionCreateParams.builder()
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
        val client = FrontendOpenAIClientProvider.get(project)
        val mf =
            MultipartField.builder<InputStream>()
                .value(inputStream)
                .contentType("audio/wav")
                .filename("audio.wav")
                .build()
        val params = TranscriptionCreateParams.builder().file(mf).model(AudioModel.WHISPER_1).build()
        val response = client.async().audio().transcriptions().createStreaming(params)
        response.subscribe { event ->
            if (event.isTranscriptTextDelta()) {
                onDelta(event.asTranscriptTextDelta().delta())
            } else if (event.isTranscriptTextDone()) {
                onDone(event.asTranscriptTextDone().text())
            }
        }
        return response.onCompleteFuture()
    }
}
