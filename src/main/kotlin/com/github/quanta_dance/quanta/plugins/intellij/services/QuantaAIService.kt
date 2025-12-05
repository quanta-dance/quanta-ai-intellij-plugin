// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.sound.AudioCapture
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.InputStream
import java.util.concurrent.CompletableFuture

@Service(Service.Level.PROJECT)
class QuantaAIService(private val project: Project) {
    private var capture: AudioCapture? = null

    companion object {
        private val logger = Logger.getInstance(QuantaAIService::class.java)
    }

    fun mute(boolean: Boolean) {
        if (boolean) capture?.mute() else capture?.unmute()
    }

    fun speakEnd() {
        try {
            capture?.stopCapture()
        } finally {
            capture = null
        }
    }

    fun speakStart(
        onSilenceDetected: () -> Unit,
        onSpeechDetected: () -> Unit,
        onMuteChanged: (Boolean) -> Unit = {},
        onPartial: (String) -> Unit = {},
        onFinal: (String) -> Unit = {},
    ) {
        val oAI = project.service<OpenAIService>()

        val aiVoice = project.service<AIVoiceService>()

        capture =
            AudioCapture(
                fullBufferCallback = { wavBytes ->
                    // Optional final fallback (block-based), can be removed if not needed
                    CompletableFuture.runAsync {
                        try {
                            val text = aiVoice.transcript(wavBytes.inputStream())
                            if (text.isNotBlank()) {
                                ApplicationManager.getApplication().invokeLater {
                                    project.service<ToolWindowService>().addUserMessage(text)
                                }
                                onFinal(text)
                                oAI.sendMessage(text)
                            }
                        } catch (t: Throwable) {
                            QDLog.warn(logger) { "Final transcription failed: ${t.message}" }
                        }
                    }
                },
                onStreamStart = { inputStream: InputStream ->
                    // TODO: this currently doesn't work. openAI client doesn't produce events
                    // Start true streaming; AudioCapture will write a WAV header and stream PCM frames.

                    //  val currentUserMessage = project.service<ToolWindowService>().addUserMessage("")
                    CompletableFuture.runAsync {
                        aiVoice.transcriptStreaming(
                            inputStream,
                            onDelta = { delta ->
                                onPartial(delta)
                                ApplicationManager.getApplication().invokeLater {
                                    //   currentUserMessage?.appendPartial(delta)
                                }
                            },
                            onDone = { fin ->
                                if (fin.isNotBlank()) {
                                    onFinal(fin)
                                    ApplicationManager.getApplication().invokeLater {
                                        //   currentUserMessage?.appendPartial(fin) // repaint
                                    }
                                    try {
                                        oAI.sendMessage(fin)
                                    } catch (_: Throwable) {
                                    }
                                }
                            },
                        )
                    }
                },
                onStreamBytes = { _, _ ->
                    // not needed; AudioCapture streams directly
                },
                onStreamEnd = {
                    // nothing to send here; AudioCapture closes stream
                },
                onMuteChanged = onMuteChanged,
            )
        capture?.startCapture(
            onSilence = {
                onSilenceDetected()
                QDLog.info(logger) { "Silence detected" }
            },
            onSpeech = {
                onSpeechDetected()
                QDLog.info(logger) { "Speech detected" }
            },
        )
    }
}
