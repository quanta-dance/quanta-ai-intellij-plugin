// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.voice

import com.github.quanta_dance.quanta.plugins.intellij.frontend.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.frontend.sound.AudioCapture
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

@Service(Service.Level.PROJECT)
class FrontendMicrophoneService(private val project: Project) {
    companion object {
        private val logger = Logger.getInstance(FrontendMicrophoneService::class.java)
        private const val MIC_PASSIVE_DELAY_MS = 600L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    private val _isVoiceDetected = MutableStateFlow(false)
    val isVoiceDetected: StateFlow<Boolean> = _isVoiceDetected.asStateFlow()

    @Volatile
    private var currentSessionId: String? = null

    @Volatile
    private var capture: AudioCapture? = null

    @Volatile
    private var micPassiveDelayJob: Job? = null

    fun toggleListening() {
        if (_isListening.value) {
            stopListening()
        } else {
            startListening()
        }
    }

    fun startListening() {
        if (_isListening.value) return
        QDLog.info(logger) { "FrontendMicrophoneService.startListening" }
        val audioCapture =
            AudioCapture(
                fullBufferCallback = {},
                onStreamStart = {
                    val sessionId = UUID.randomUUID().toString()
                    currentSessionId = sessionId
                    QDLog.info(logger) { "FrontendMicrophoneService.onStreamStart sessionId=$sessionId" }
                    scope.launch {
                        runCatching {
                            QuantaBackendApi.getInstance().startMicrophoneSession(project.projectId(), sessionId)
                        }.onFailure { error ->
                            QDLog.warn(
                                logger,
                                { "FrontendMicrophoneService.startSession failed: ${error.message}" },
                                error
                            )
                        }
                    }
                },
                onStreamBytes = { bytes, length ->
                    val sessionId = currentSessionId ?: return@AudioCapture
                    val chunkBase64 = Base64.getEncoder().encodeToString(bytes.copyOf(length))
                    scope.launch {
                        runCatching {
                            QuantaBackendApi.getInstance()
                                .appendMicrophoneAudioChunk(project.projectId(), sessionId, chunkBase64)
                        }.onFailure { error ->
                            QDLog.warn(
                                logger,
                                { "FrontendMicrophoneService.appendChunk failed: ${error.message}" },
                                error
                            )
                        }
                    }
                },
                onStreamEnd = {
                    val sessionId = currentSessionId ?: return@AudioCapture
                    currentSessionId = null
                    QDLog.info(logger) { "FrontendMicrophoneService.onStreamEnd sessionId=$sessionId" }
                    scope.launch {
                        runCatching {
                            val result =
                                QuantaBackendApi.getInstance().finishMicrophoneSession(project.projectId(), sessionId)
                            QDLog.info(logger) {
                                "FrontendMicrophoneService.finishSession sessionId=$sessionId submitted=${result.submitted} transcript=${
                                    result.transcript.take(
                                        120
                                    )
                                }"
                            }
                        }.onFailure { error ->
                            QDLog.warn(
                                logger,
                                { "FrontendMicrophoneService.finishSession failed: ${error.message}" },
                                error
                            )
                        }
                    }
                },
            )
        capture = audioCapture
        audioCapture.startCapture(
            onSilence = {
                micPassiveDelayJob?.cancel()
                micPassiveDelayJob =
                    scope.launch {
                        delay(MIC_PASSIVE_DELAY_MS)
                        _isVoiceDetected.value = false
                    }
            },
            onSpeech = {
                micPassiveDelayJob?.cancel()
                _isVoiceDetected.value = true
            },
        )
        _isListening.value = true
        _isVoiceDetected.value = false
    }

    fun stopListening() {
        if (!_isListening.value) return
        QDLog.info(logger) { "FrontendMicrophoneService.stopListening" }
        micPassiveDelayJob?.cancel()
        micPassiveDelayJob = null
        _isListening.value = false
        _isVoiceDetected.value = false
        capture?.stopCapture()
        capture = null
        val sessionId = currentSessionId
        currentSessionId = null
        if (sessionId != null) {
            scope.launch {
                runCatching {
                    QuantaBackendApi.getInstance().cancelMicrophoneSession(project.projectId(), sessionId)
                }.onFailure { error ->
                    QDLog.warn(logger, { "FrontendMicrophoneService.cancelSession failed: ${error.message}" }, error)
                }
            }
        }
    }
}
