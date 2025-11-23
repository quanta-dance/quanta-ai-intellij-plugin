package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

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
        val useLocalMacTts = System.getProperty("os.name").contains("Mac", ignoreCase = true)
                && QuantaAISettingsState.instance.state.voiceByLocalTTS
        if (useLocalMacTts) {
            val th = Thread {
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
            // Use OpenAI speech (async)
            project.service<OpenAIService>().speech(message) { mp3Stream ->
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
}
