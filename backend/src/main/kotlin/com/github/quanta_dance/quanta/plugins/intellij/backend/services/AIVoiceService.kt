// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.SpeechChunkDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * Backend text-to-speech service.
 *
 * Provides both one-shot synthesis and streaming speech generation.
 * The frontend uses the streaming helpers to play back audio in chunks.
 */
@Service(Service.Level.PROJECT)
class AIVoiceService(
    private val project: Project,
) {
    companion object {
        private val logger = Logger.getInstance(AIVoiceService::class.java)
        private const val STREAM_CHUNK_SIZE = 16 * 1024
    }

    private data class SpeechStreamState(
        val chunks: LinkedBlockingQueue<SpeechChunkDto> = LinkedBlockingQueue(),
        @Volatile var finished: Boolean = false,
    )

    private val streams = ConcurrentHashMap<String, SpeechStreamState>()
    private val executionContexts = project.getService(BackendExecutionContextsService::class.java)
    private val httpClient: HttpClient = HttpClient.newHttpClient()
    private val objectMapper = jacksonObjectMapper()

    @Volatile
    private var currentStreamJob: Job? = null

    /**
     * Synthesize a complete MP3 clip for the given text.
     */
    fun say(message: String): ByteArray {
        val text = message.trim()
        if (text.isEmpty()) return ByteArray(0)
        QDLog.info(logger) { "AIVoiceService.say: synthesizing speech for ${text.take(80)}" }

        val settings = BackendRuntimeSettingsService.instance.settings
        val baseUrl = settings.openAiUrl.trim().trimEnd('/')
        val requestJson =
            objectMapper.writeValueAsString(
                mapOf(
                    "model" to "gpt-4o-mini-tts",
                    "input" to text,
                    "voice" to selectedOpenAiVoice(),
                    "response_format" to "mp3",
                ),
            )

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("$baseUrl/audio/speech"))
                .header("Authorization", "Bearer ${settings.openAiToken}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            error("${response.statusCode()}: ${response.body().decodeToString()}")
        }
        val bytes = response.body()
        QDLog.info(logger) { "AIVoiceService.say: synthesized ${bytes.size} bytes" }
        return bytes
    }

    /**
     * Start a streaming speech request for the given session.
     *
     * The generated PCM chunks are buffered in-memory for frontend polling.
     */
    fun startSpeechStream(
        sessionId: String,
        message: String,
    ) {
        stopTalking()
        val text = message.trim()
        if (text.isEmpty()) {
            streams[sessionId] = SpeechStreamState().apply { finished = true }
            return
        }

        val state = SpeechStreamState()
        streams[sessionId] = state

        currentStreamJob =
            executionContexts.voiceStreamingScope.launch {
                try {
                    val settings = BackendRuntimeSettingsService.instance.settings
                    val baseUrl = settings.openAiUrl.trim().trimEnd('/')
                    val requestJson =
                        objectMapper.writeValueAsString(
                            mapOf(
                                "model" to "gpt-4o-mini-tts",
                                "input" to text,
                                "voice" to selectedOpenAiVoice(),
                                "response_format" to "pcm",
                            ),
                        )

                    val request =
                        HttpRequest
                            .newBuilder()
                            .uri(URI.create("$baseUrl/audio/speech"))
                            .header("Authorization", "Bearer ${settings.openAiToken}")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                            .build()

                    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    response.body().use { input ->
                        if (response.statusCode() !in 200..299) {
                            error("${response.statusCode()}: ${input.readBytes().decodeToString()}")
                        }
                        val buffer = ByteArray(STREAM_CHUNK_SIZE)
                        var sequence = 0
                        var pendingOddByte: Byte? = null
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break

                            val rawChunk =
                                if (pendingOddByte != null) {
                                    byteArrayOf(pendingOddByte!!) + buffer.copyOf(read)
                                } else {
                                    buffer.copyOf(read)
                                }
                            pendingOddByte = null

                            val evenLength = rawChunk.size - (rawChunk.size % 2)
                            if (evenLength <= 0) {
                                pendingOddByte = rawChunk.lastOrNull()
                                continue
                            }
                            if (evenLength < rawChunk.size) {
                                pendingOddByte = rawChunk.last()
                            }

                            val pcmChunk = rawChunk.copyOf(evenLength)
                            val currentSequence = sequence++
                            QDLog.info(
                                logger,
                            ) { "AIVoiceService.stream enqueue sessionId=$sessionId sequence=$currentSequence bytes=${pcmChunk.size}" }
                            state.chunks.put(
                                SpeechChunkDto(
                                    sessionId = sessionId,
                                    chunkBase64 = Base64.getEncoder().encodeToString(pcmChunk),
                                    sequence = currentSequence,
                                    isLast = false,
                                ),
                            )
                        }
                        if (pendingOddByte != null) {
                            QDLog.info(logger) { "AIVoiceService.stream dropping dangling odd byte sessionId=$sessionId" }
                        }
                        QDLog.info(logger) { "AIVoiceService.stream enqueue-last sessionId=$sessionId sequence=$sequence" }
                        state.chunks.put(
                            SpeechChunkDto(
                                sessionId = sessionId,
                                chunkBase64 = "",
                                sequence = sequence,
                                isLast = true,
                            ),
                        )
                    }
                } catch (t: Throwable) {
                    QDLog.warn(logger, { "AIVoiceService.startSpeechStream failed: ${t.message}" }, t)
                    state.chunks.offer(
                        SpeechChunkDto(
                            sessionId = sessionId,
                            chunkBase64 = "",
                            sequence = Int.MAX_VALUE,
                            isLast = true,
                        ),
                    )
                } finally {
                    state.finished = true
                }
            }
    }

    /**
     * Poll the next available chunk for a session stream.
     */
    fun pollSpeechChunk(
        sessionId: String,
        afterSequence: Int,
    ): SpeechChunkDto {
        val state =
            streams[sessionId] ?: return SpeechChunkDto(sessionId = sessionId, sequence = afterSequence, isLast = true)
        val next = state.chunks.poll()
        if (next != null) {
            QDLog.info(logger) {
                "AIVoiceService.stream poll sessionId=$sessionId requestedAfter=$afterSequence returnedSequence=${next.sequence} isLast=${next.isLast} bytesBase64=${next.chunkBase64.length}"
            }
            if (next.isLast) {
                streams.remove(sessionId)
            }
            return next
        }
        if (state.finished) {
            QDLog.info(logger) { "AIVoiceService.stream poll-finished-empty sessionId=$sessionId requestedAfter=$afterSequence" }
            streams.remove(sessionId)
            return SpeechChunkDto(sessionId = sessionId, sequence = afterSequence, isLast = true)
        }
        return SpeechChunkDto(sessionId = sessionId, sequence = afterSequence, isLast = false)
    }

    private fun selectedOpenAiVoice(): String =
        BackendRuntimeSettingsService.instance.settings.preferredOpenAiTtsVoice
            .trim()
            .ifBlank { "ash" }

    /**
     * Stop any active speech streams and clear buffered chunks.
     */
    fun stopTalking() {
        currentStreamJob?.cancel()
        currentStreamJob = null
        streams.clear()
    }
}
