// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.sound

import com.github.quanta_dance.quanta.plugins.intellij.frontend.QDLog
import com.intellij.openapi.diagnostic.Logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

/**
 * AudioCapture streams microphone audio and exposes a per-utterance InputStream for consumers.
 * It will emit onStreamStart with an InputStream when speech is detected, write a minimal WAV
 * header followed by PCM frames to that stream while inSpeech, and close it on onStreamEnd.
 */
class AudioCapture(
    private val fullBufferCallback: (ByteArray) -> Unit,
    private val onStreamStart: ((inputStream: InputStream) -> Unit)? = null,
    private val onStreamBytes: ((bytes: ByteArray, length: Int) -> Unit)? = null,
    private val onStreamEnd: (() -> Unit)? = null,
    private val onMuteChanged: ((Boolean) -> Unit)? = null,
) {
    var silenceStart: Long = -1
    var speechStart: Long = -1
    var inSpeech: Boolean = false
    var inSilence: Boolean = false

    @Volatile
    var isMuted: Boolean = false
        private set

    private var outputBuffer = ByteArrayOutputStream()

    private val audioFormat =
        AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            16000f,
            16,
            1,
            2,
            16000f,
            false,
        )

    private fun averageAmplitude(
        buffer: ByteArray,
        length: Int,
    ): Long {
        if (length <= 1) return 0
        var sum: Long = 0
        var samples = 0
        var i = 0
        val limit = length - 1
        while (i < limit) {
            val lo = buffer[i].toInt() and 0xFF
            val hi = buffer[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            sum += kotlin.math.abs(sample.toLong())
            samples += 1
            i += 2
        }
        if (samples == 0) return 0
        return sum / samples
    }

    private fun isSilent(
        buffer: ByteArray,
        length: Int,
    ): Boolean = averageAmplitude(buffer, length) < SILENCE_THRESHOLD

    private val line: TargetDataLine
    private val isCapturing = AtomicBoolean(false)

    @Volatile
    private var captureThread: Thread? = null

    init {
        val info = DataLine.Info(TargetDataLine::class.java, audioFormat)
        if (!AudioSystem.isLineSupported(info)) {
            throw LineUnavailableException("Line not supported")
        }
        line = AudioSystem.getLine(info) as TargetDataLine
        line.open(audioFormat)
    }

    fun convertPcmToMp3(
        pcmBytes: ByteArray,
        sampleRate: Float = 16000.0f,
        channels: Int = 1,
    ): ByteArray {
        @Suppress("UNUSED_PARAMETER")
        val ignoredSampleRate = sampleRate

        @Suppress("UNUSED_PARAMETER")
        val ignoredChannels = channels
        QDLog.warn(logger) { "MP3 conversion is unavailable in the frontend runtime; returning raw PCM bytes." }
        return pcmBytes
    }

    fun wrapAsWav(rawData: ByteArray): ByteArray {
        val bais = ByteArrayInputStream(rawData)
        val audioInputStream = AudioInputStream(bais, audioFormat, rawData.size / audioFormat.frameSize.toLong())
        val wavOutput = ByteArrayOutputStream()
        AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, wavOutput)
        return wavOutput.toByteArray()
    }

    private fun writeWavHeader(out: OutputStream) {
        val sampleRate = 16000
        val bitsPerSample = 16
        val channels = 1
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArrayOutputStream(44)

        fun writeLE32(v: Int) {
            header.write(
                byteArrayOf(
                    (v and 0xFF).toByte(),
                    ((v shr 8) and 0xFF).toByte(),
                    ((v shr 16) and 0xFF).toByte(),
                    ((v shr 24) and 0xFF).toByte(),
                ),
            )
        }

        fun writeLE16(v: Int) {
            header.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
        }
        header.write("RIFF".toByteArray())
        writeLE32(0) // placeholder chunk size
        header.write("WAVE".toByteArray())
        header.write("fmt ".toByteArray())
        writeLE32(16) // PCM fmt chunk size
        writeLE16(1) // PCM format
        writeLE16(channels)
        writeLE32(sampleRate)
        writeLE32(byteRate)
        writeLE16(blockAlign)
        writeLE16(bitsPerSample)
        header.write("data".toByteArray())
        writeLE32(0) // placeholder data size
        out.write(header.toByteArray())
        out.flush()
    }

    fun startCapture(
        onSilence: () -> Unit = {},
        onSpeech: () -> Unit = {},
    ) {
        if (!isCapturing.compareAndSet(false, true)) {
            QDLog.debug(logger) { "Already capturing" }
            return
        }
        QDLog.info(logger) { "Capture started" }
        val worker =
            Thread {
                try {
                    line.start()
                    val buffer = ByteArray(2048)
                    var lastAudioLevelLogAt = 0L
                    while (isCapturing.get() && !Thread.currentThread().isInterrupted) {
                        val bytesRead = line.read(buffer, 0, buffer.size)
                        val now = System.currentTimeMillis()

                        if (bytesRead > 0) {
                            val avgAmplitude = averageAmplitude(buffer, bytesRead)
                            if (now - lastAudioLevelLogAt >= AUDIO_LEVEL_LOG_INTERVAL_MS) {
                                QDLog.info(logger) {
                                    "AudioCapture.level avgAmplitude=$avgAmplitude silent=${avgAmplitude < SILENCE_THRESHOLD} muted=$isMuted inSpeech=$inSpeech"
                                }
                                lastAudioLevelLogAt = now
                            }

                            val silent = isMuted || avgAmplitude < SILENCE_THRESHOLD
                            if (silent) {
                                if (!inSilence) {
                                    inSilence = true
                                    QDLog.info(logger) { "AudioCapture.silenceDetected avgAmplitude=$avgAmplitude" }
                                    onSilence()
                                    silenceStart = now
                                }
                            } else {
                                if (inSilence) {
                                    QDLog.info(logger) { "AudioCapture.speechDetected avgAmplitude=$avgAmplitude" }
                                    onSpeech()
                                }
                                inSilence = false
                                if (silenceStart != -1L) silenceStart = -1
                                if (!inSpeech) {
                                    inSpeech = true
                                    speechStart = now
                                    QDLog.info(logger) { "AudioCapture.streamStart avgAmplitude=$avgAmplitude" }
                                    try {
                                        onStreamStart?.invoke(ByteArrayInputStream(ByteArray(0)))
                                    } catch (t: Throwable) {
                                        QDLog.warn(logger) { "onStreamStart failed: ${t.message}" }
                                    }
                                }
                            }

                            if (inSpeech && !isMuted) {
                                outputBuffer.write(buffer, 0, bytesRead)
                                try {
                                    onStreamBytes?.invoke(buffer.copyOfRange(0, bytesRead), bytesRead)
                                } catch (t: Throwable) {
                                    QDLog.warn(logger) { "onStreamBytes failed: ${t.message}" }
                                }
                            }
                        }

                        if (inSpeech && inSilence && silenceStart > 0 && now - silenceStart >= SPEECH_PAUSE_DURATION_MIN_MS) {
                            inSpeech = false
                            val speechDuration = now - speechStart
                            QDLog.info(
                                logger,
                            ) {
                                "AudioCapture.streamEnd reason=silence speechDurationMs=$speechDuration bufferedBytes=${outputBuffer.size()}"
                            }
                            try {
                                onStreamEnd?.invoke()
                            } catch (_: Throwable) {
                            }
                            if (speechDuration >= SPEECH_LENGHT_MIN_MS) {
                                val audio = wrapAsWav(outputBuffer.toByteArray())
                                fullBufferCallback(audio)
                            } else {
                                QDLog.info(logger) { "AudioCapture.segmentDropped reason=tooShort speechDurationMs=$speechDuration" }
                            }
                            outputBuffer.reset()
                            if (!inSilence) {
                                inSilence = true
                                onSilence()
                            }
                            silenceStart = now
                        }

                        if (inSpeech && now - speechStart >= MAX_SPEECH_SEGMENT_MS) {
                            inSpeech = false
                            try {
                                onStreamEnd?.invoke()
                            } catch (_: Throwable) {
                            }
                            val audio = wrapAsWav(outputBuffer.toByteArray())
                            fullBufferCallback(audio)
                            outputBuffer.reset()
                            if (!inSilence) {
                                inSilence = true
                                onSilence()
                            }
                            silenceStart = now
                        }
                    }
                } catch (t: Throwable) {
                    QDLog.warn(logger, { "Capture loop terminated: ${t.message}" })
                } finally {
                    try {
                        line.stop()
                    } catch (_: Throwable) {
                    }
                    try {
                        line.flush()
                    } catch (_: Throwable) {
                    }
                    try {
                        line.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        worker.isDaemon = true
        captureThread = worker
        worker.start()
    }

    fun mute() {
        if (isMuted) return
        isMuted = true
        val now = System.currentTimeMillis()
        val wasInSpeech = inSpeech
        inSpeech = false
        if (wasInSpeech) {
            try {
                onStreamEnd?.invoke()
            } catch (_: Throwable) {
            }
        }
        outputBuffer.reset()
        if (!inSilence) {
            inSilence = true
        }
        silenceStart = now
        try {
            onMuteChanged?.invoke(true)
        } catch (_: Throwable) {
        }
    }

    fun unmute() {
        if (!isMuted) return
        isMuted = false
        try {
            onMuteChanged?.invoke(false)
        } catch (_: Throwable) {
        }
    }

    fun stopCapture() {
        if (!isCapturing.compareAndSet(true, false)) return
        captureThread?.interrupt()
        try {
            captureThread?.join(1000)
        } catch (_: Throwable) {
        }
        captureThread = null
        outputBuffer.reset()
        QDLog.info(logger) { "Capture stopped" }
    }

    companion object {
        private val logger = Logger.getInstance(AudioCapture::class.java)
        const val SILENCE_THRESHOLD: Int = 1_200
        const val SILENCE_DURATION_MS: Int = 350
        const val SPEECH_LENGHT_MIN_MS: Int = 1200
        const val SPEECH_PAUSE_DURATION_MIN_MS: Int = 900
        const val MAX_SPEECH_SEGMENT_MS: Int = 15000
        const val AUDIO_LEVEL_LOG_INTERVAL_MS: Long = 1000

        @JvmStatic
        fun main(args: Array<String>) {
            val capture =
                AudioCapture(
                    fullBufferCallback = {
                        // WAV segment
                    },
                    onStreamStart = { _ ->
                        // input stream
                    },
                    onStreamBytes = { bytes, _ ->
                        // optional, for debug
                    },
                    onStreamEnd = {
                        // close streaming
                    },
                    onMuteChanged = { _ ->
                        // update UI
                    },
                )
            thread { capture.startCapture(onSilence = {}, onSpeech = {}) }
            Thread.sleep(60000)
            capture.stopCapture()
        }
    }
}
