// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.voice

import com.github.quanta_dance.quanta.plugins.intellij.frontend.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.sound.Player
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.*

@Service(Service.Level.PROJECT)
class FrontendAIVoiceService(private val project: Project) {
    private var process: Process? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val logger = Logger.getInstance(FrontendAIVoiceService::class.java)
    }

    private fun markPlayback(stage: String, details: String) {
        QDLog.info(logger) { "FrontendAIVoiceService[$stage]: $details" }
//        runCatching {
//            NotificationGroupManager
//                .getInstance()
//                .getNotificationGroup("Plugin Notifications")
//                .createNotification("Voice/$stage", details, NotificationType.INFORMATION)
//                .notify(project)
//        }
    }

    fun stopTalking() {
        try {
            QDLog.info(logger) { "FrontendAIVoiceService.stopTalking: stopping local playback/process" }
            process?.destroy()
        } catch (_: Throwable) {
        }
        try {
            Player.stop()
        } catch (_: Throwable) {
        }
        scope.launch {
            runCatching {
                QuantaBackendApi.getInstance().stopSpeech(project.projectId())
            }.onFailure { e ->
                QDLog.warn(logger, { "FrontendAIVoiceService.stopTalking: backend stopSpeech failed: ${e.message}" }, e)
            }
        }
    }

    fun say(message: String) {
        if (!FrontendQuantaSettingsState.instance.state.voiceEnabled) return
        stopTalking()
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

        scope.launch {
            runCatching {
                markPlayback("rpc", "calling backend synthesizeSpeech")
                val response = QuantaBackendApi.getInstance().synthesizeSpeech(project.projectId(), message)
                if (response.audioBase64.isBlank()) {
                    QDLog.warn(logger) { "FrontendAIVoiceService.say: backend returned empty audio payload" }
                    markPlayback("rpc", "backend returned empty audio payload")
                    return@runCatching
                }
                val audioBytes = Base64.getDecoder().decode(response.audioBase64)
                markPlayback("rpc", "received ${audioBytes.size} audio bytes from backend")
                playAudioBytes(audioBytes)
            }.onFailure { e ->
                QDLog.error(logger, { "Backend speech synthesis/playback failed" }, e)
            }
        }
    }

    private fun playAudioBytes(audioBytes: ByteArray) {
        Player.playMp3(ByteArrayInputStream(audioBytes)) {
            markPlayback("jlayer", "playback finished")
        }
    }
}
