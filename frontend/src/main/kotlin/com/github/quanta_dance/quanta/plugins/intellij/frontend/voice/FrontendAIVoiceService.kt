// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.voice

import com.github.quanta_dance.quanta.plugins.intellij.frontend.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.sound.Player
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.FrontendLogDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.FrontendLogLevel
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import kotlinx.coroutines.*
import java.util.*

@Service(Service.Level.PROJECT)
class FrontendAIVoiceService(private val project: Project) {
    private var process: Process? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentSpeechJob: Job? = null

    companion object {
        private val logger = Logger.getInstance(FrontendAIVoiceService::class.java)
    }

    private fun logFrontend(
        level: FrontendLogLevel,
        message: String,
    ) {
        when (level) {
            FrontendLogLevel.DEBUG -> QDLog.debug(logger) { message }
            FrontendLogLevel.INFO -> QDLog.info(logger) { message }
            FrontendLogLevel.WARN -> QDLog.warn(logger) { message }
            FrontendLogLevel.ERROR -> QDLog.error(logger, { message }, null)
        }
        scope.launch {
            runCatching {
                QuantaBackendApi.getInstance().logFrontend(
                    project.projectId(),
                    FrontendLogDto(level = level, message = message),
                )
            }
        }
    }

    private fun markPlayback(stage: String, details: String) {
        logFrontend(FrontendLogLevel.INFO, "FrontendAIVoiceService[$stage]: $details")
    }

    fun stopTalking() {
        currentSpeechJob?.cancel()
        currentSpeechJob = null
        stopTalkingInternal(stopBackend = true)
    }

    private fun stopTalkingInternal(stopBackend: Boolean) {
        try {
            QDLog.info(logger) { "FrontendAIVoiceService.stopTalking: stopping local playback/process" }
            process?.destroy()
        } catch (_: Throwable) {
        }
        try {
            Player.stop()
        } catch (_: Throwable) {
        }
        if (stopBackend) {
            scope.launch {
                runCatching {
                    QuantaBackendApi.getInstance().stopSpeech(project.projectId())
                }.onFailure { e ->
                    QDLog.warn(
                        logger,
                        { "FrontendAIVoiceService.stopTalking: backend stopSpeech failed: ${e.message}" },
                        e
                    )
                }
            }
        }
    }

    fun say(message: String) {
        if (!FrontendQuantaSettingsState.instance.state.voiceEnabled) return
        currentSpeechJob?.cancel()
        currentSpeechJob = null
        stopTalkingInternal(stopBackend = false)
        markPlayback("say", "requested speech for ${message.take(80)}")

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
                    }
                }
            th.isDaemon = true
            th.start()
            return
        }

        currentSpeechJob =
            scope.launch {
                runCatching {
                    val sessionId = UUID.randomUUID().toString()
                    val backendApi = QuantaBackendApi.getInstance()
                    markPlayback("stream-start", "starting streamed speech session=$sessionId")
                    val feed =
                        Player.startStreamingPcm(
                            onDebugLog = { level, debug ->
                                scope.launch {
                                    runCatching {
                                        backendApi.logFrontend(
                                            project.projectId(),
                                            FrontendLogDto(level = level, message = debug),
                                        )
                                    }
                                }
                            },
                        )
                    backendApi.startSpeechStream(project.projectId(), sessionId, message)
                    var last = false
                    var lastSequence = -1
                    while (isActive && !last) {
                        val chunk =
                            backendApi.pollSpeechChunk(project.projectId(), sessionId, lastSequence)
                        if (chunk.sequence == lastSequence && chunk.chunkBase64.isBlank() && !chunk.isLast) {
                            delay(40)
                            continue
                        }
                        lastSequence = chunk.sequence
                        val bytes = if (chunk.chunkBase64.isNotBlank()) Base64.getDecoder()
                            .decode(chunk.chunkBase64) else ByteArray(0)
                        val msg =
                            "FrontendAIVoiceService.stream receive sessionId=$sessionId sequence=${chunk.sequence} isLast=${chunk.isLast} bytes=${bytes.size}"
                        logFrontend(FrontendLogLevel.INFO, msg)
                        feed(bytes, chunk.isLast)
                        last = chunk.isLast
                    }
                    markPlayback("stream-finished", "finished streamed speech session=$sessionId")
                }.onFailure { t ->
                    logFrontend(FrontendLogLevel.ERROR, "FrontendAIVoiceService.say failed: ${t.message}")
                    Player.stop()
                }
            }
    }
}
