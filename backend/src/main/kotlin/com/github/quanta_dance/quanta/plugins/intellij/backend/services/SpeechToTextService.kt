// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationService
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * Backend speech-to-text service.
 *
 * Collects PCM audio for a voice session, wraps it as WAV, and sends it to the
 * OpenAI transcription endpoint. The backend keeps session lifecycle and request
 * construction centralized here.
 *
 * TODO: split raw PCM buffering from transcription transport if this service grows further.
 */
@Service(Service.Level.PROJECT)
class SpeechToTextService(
    private val project: Project,
) {
    companion object {
        private val logger = Logger.getInstance(SpeechToTextService::class.java)
        private const val MIN_PCM_BYTES = 4_096
    }

    private val sessions = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val audioFormat = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16000f, 16, 1, 2, 16000f, false)
    private val httpClient: HttpClient = HttpClient.newHttpClient()
    private val objectMapper = jacksonObjectMapper()

    /**
     * Start a new capture session and reset any previous buffered audio.
     */
    fun startSession(sessionId: String) {
        sessions[sessionId]?.close()
        sessions[sessionId] = ByteArrayOutputStream()
        QDLog.info(logger) { "SpeechToTextService.startSession: $sessionId" }
    }

    /**
     * Append a PCM audio chunk to the active capture session.
     */
    fun appendAudioChunk(
        sessionId: String,
        chunk: ByteArray,
    ) {
        if (chunk.isEmpty()) return
        val buffer = sessions[sessionId]
        if (buffer == null) {
            QDLog.info(
                logger,
            ) { "SpeechToTextService.appendAudioChunk: ignoring chunk for inactive sessionId=$sessionId bytes=${chunk.size}" }
            return
        }
        synchronized(buffer) {
            buffer.write(chunk)
            val totalBytes = buffer.size()
            if (totalBytes == chunk.size || totalBytes % 32768 < chunk.size) {
                QDLog.info(logger) { "SpeechToTextService.appendAudioChunk: sessionId=$sessionId totalBytes=$totalBytes" }
            }
        }
    }

    /**
     * Cancel and discard a capture session without transcription.
     */
    fun cancelSession(sessionId: String) {
        sessions.remove(sessionId)?.close()
        QDLog.info(logger) { "SpeechToTextService.cancelSession: $sessionId" }
    }

    /**
     * Finish a capture session, transcribe the audio, and return the text.
     */
    suspend fun finishSession(sessionId: String): String {
        val buffer = sessions.remove(sessionId) ?: return ""
        val pcmBytes = synchronized(buffer) { buffer.toByteArray() }
        buffer.close()
        QDLog.info(logger) { "SpeechToTextService.finishSession: sessionId=$sessionId pcmBytes=${pcmBytes.size}" }
        if (pcmBytes.size < MIN_PCM_BYTES) {
            QDLog.info(logger) { "SpeechToTextService.finishSession: skipping short capture for $sessionId (${pcmBytes.size} bytes)" }
            return ""
        }

        return try {
            val wavBytes = wrapPcmAsWav(pcmBytes)
            QDLog.info(
                logger,
            ) { "SpeechToTextService.finishSession: starting transcription sessionId=$sessionId wavBytes=${wavBytes.size}" }
            val transcript = transcribe(wavBytes).trim()
            QDLog.info(logger) { "SpeechToTextService.finishSession: transcript=${transcript.take(160)}" }
            if (transcript.isNotEmpty()) {
                project.service<ChatConversationService>().sendUserMessage(transcript)
            }
            transcript
        } catch (t: Throwable) {
            QDLog.warn(logger, { "SpeechToTextService.finishSession failed for $sessionId: ${t.message}" }, t)
            throw t
        }
    }

    private fun transcribe(wavBytes: ByteArray): String {
        QDLog.info(logger) { "SpeechToTextService.transcribe: requestBytes=${wavBytes.size}" }

        val settings = BackendRuntimeSettingsService.instance.settings
        val baseUrl = settings.openAiUrl.trim().trimEnd('/')
        val boundary = "----QuantaSttBoundary${UUID.randomUUID()}"
        val body = buildMultipartBody(boundary, wavBytes)
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("$baseUrl/audio/transcriptions"))
                .header("Authorization", "Bearer ${settings.openAiToken}")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("${response.statusCode()}: ${response.body()}")
        }

        val text =
            objectMapper
                .readTree(response.body())
                .path("text")
                .asText("")
                .trim()
        QDLog.info(logger) { "SpeechToTextService.transcribe: responseText=${text.take(160)}" }
        return text
    }

    private fun buildMultipartBody(
        boundary: String,
        wavBytes: ByteArray,
    ): ByteArray {
        val newline = "\r\n"
        val output = ByteArrayOutputStream()

        fun writeText(value: String) {
            output.write(value.toByteArray(Charsets.UTF_8))
        }

        writeText("--$boundary$newline")
        writeText("Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"$newline")
        writeText("Content-Type: audio/wav$newline$newline")
        output.write(wavBytes)
        writeText(newline)

        writeText("--$boundary$newline")
        writeText("Content-Disposition: form-data; name=\"model\"$newline$newline")
        writeText("gpt-4o-mini-transcribe$newline")

        writeText("--$boundary$newline")
        writeText("Content-Disposition: form-data; name=\"language\"$newline$newline")
        writeText("en$newline")

        writeText("--$boundary--$newline")
        return output.toByteArray()
    }

    private fun wrapPcmAsWav(rawData: ByteArray): ByteArray {
        val bais = ByteArrayInputStream(rawData)
        val audioInputStream = AudioInputStream(bais, audioFormat, rawData.size / audioFormat.frameSize.toLong())
        val wavOutput = ByteArrayOutputStream()
        AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, wavOutput)
        return wavOutput.toByteArray()
    }
}
