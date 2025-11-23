package com.github.quanta_dance.quanta.plugins.intellij.sound

import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.intellij.openapi.diagnostic.Logger
import vavi.sound.sampled.lamejb.LamejbFormatConversionProvider
import java.io.*
import javax.sound.sampled.*
import kotlin.concurrent.thread
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicBoolean

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

    private val audioFormat = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        16000f,
        16,
        1,
        2,
        16000f,
        false
    )

    private fun isSilent(buffer: ByteArray, length: Int): Boolean {
        var sum: Long = 0
        var i = 0
        val limit = (length - 1).coerceAtLeast(0)
        while (i < limit) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            sum += kotlin.math.abs(sample.toLong())
            i += 2
        }
        if (length <= 0) return true
        val avgAmplitude = sum / (length / 2)
        return avgAmplitude < SILENCE_THRESHOLD
    }

    private val line: TargetDataLine
    private val isCapturing = AtomicBoolean(false)
    @Volatile private var captureThread: Thread? = null

    // Streaming pipe for current utterance
    @Volatile private var currentPipeOut: PipedOutputStream? = null

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
        channels: Int = 1
    ): ByteArray {
        val pcmFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            sampleRate,
            16,
            channels,
            channels * 2,
            sampleRate,
            false
        )
        val provider = LamejbFormatConversionProvider()
        return AudioInputStream(ByteArrayInputStream(pcmBytes), pcmFormat, pcmBytes.size.toLong() / pcmFormat.frameSize).use { pcmStream ->
            QDLog.debug(logger) { "PCM input format: ${pcmStream.format}" }
            val mp3Encoding = AudioFormat.Encoding("MPEG1L3")
            val targetFormats = provider.getTargetFormats(mp3Encoding, pcmStream.format)
            if (targetFormats.isEmpty()) {
                throw IllegalArgumentException("No MP3 target formats supported for PCM input format")
            }
            val targetFormat = targetFormats[0]
            QDLog.debug(logger) { "Using target MP3 format: $targetFormat" }
            provider.getAudioInputStream(targetFormat, pcmStream).use { mp3Stream ->
                val buffer = ByteArrayOutputStream()
                val tmp = ByteArray(4096)
                var bytesRead: Int
                while (mp3Stream.read(tmp).also { bytesRead = it } != -1) {
                    buffer.write(tmp, 0, bytesRead)
                }
                buffer.toByteArray()
            }
        }
    }

    fun wrapAsWav(rawData: ByteArray): ByteArray {
        val bais = ByteArrayInputStream(rawData)
        val audioInputStream = AudioInputStream(bais, audioFormat, rawData.size / audioFormat.frameSize.toLong())
        val wavOutput = ByteArrayOutputStream()
        AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, wavOutput)
        return wavOutput.toByteArray()
    }

    private fun writeWavHeader(out: OutputStream) {
        // Minimal 44-byte PCM WAV header with placeholder sizes
        val sampleRate = 16000
        val bitsPerSample = 16
        val channels = 1
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArrayOutputStream(44)
        fun writeLE32(v: Int) { header.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())) }
        fun writeLE16(v: Int) { header.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())) }
        header.write("RIFF".toByteArray())
        writeLE32(0) // placeholder chunk size
        header.write("WAVE".toByteArray())
        header.write("fmt ".toByteArray())
        writeLE32(16) // PCM fmt chunk size
        writeLE16(1)  // PCM format
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
        val worker = Thread {
            try {
                line.start()
                val buffer = ByteArray(2048)
                while (isCapturing.get() && !Thread.currentThread().isInterrupted) {
                    val bytesRead = line.read(buffer, 0, buffer.size)
                    val now = System.currentTimeMillis()

                    if (bytesRead > 0) {
                        val silent = isMuted || isSilent(buffer, bytesRead)
                        if (silent) {
                            if (!inSilence) {
                                inSilence = true
                                onSilence()
                                silenceStart = now
                            }
                        } else {
                            if (inSilence) onSpeech()
                            inSilence = false
                            if (silenceStart != -1L) silenceStart = -1
                            if (!inSpeech) {
                                inSpeech = true
                                speechStart = now
                                try {
                                    // Create a new pipe and publish the InputStream to consumer
                                    val pos = PipedOutputStream()
                                    currentPipeOut = pos
                                    val pis = PipedInputStream(pos)
                                    // Write a minimal WAV header first
                                    writeWavHeader(pos)
                                    onStreamStart?.invoke(pis)
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
                            // Stream raw PCM payload after header
                            try {
                                currentPipeOut?.write(buffer, 0, bytesRead)
                                currentPipeOut?.flush()
                            } catch (t: Throwable) {
                                QDLog.warn(logger) { "Pipe write failed: ${t.message}" }
                            }
                        }
                    }

                    if (inSpeech && inSilence && silenceStart > 0 && now - silenceStart >= SPEECH_PAUSE_DURATION_MIN_MS) {
                        inSpeech = false
                        try { onStreamEnd?.invoke() } catch (_: Throwable) {}
                        try { currentPipeOut?.close() } catch (_: Throwable) {}
                        currentPipeOut = null
                        if (now - speechStart >= SPEECH_LENGHT_MIN_MS) {
                            val audio = wrapAsWav(outputBuffer.toByteArray())
                            fullBufferCallback(audio)
                        }
                        outputBuffer.reset()
                        if (!inSilence) { inSilence = true; onSilence() }
                        silenceStart = now
                    }

                    if (inSpeech && now - speechStart >= MAX_SPEECH_SEGMENT_MS) {
                        inSpeech = false
                        try { onStreamEnd?.invoke() } catch (_: Throwable) {}
                        try { currentPipeOut?.close() } catch (_: Throwable) {}
                        currentPipeOut = null
                        val audio = wrapAsWav(outputBuffer.toByteArray())
                        fullBufferCallback(audio)
                        outputBuffer.reset()
                        if (!inSilence) { inSilence = true; onSilence() }
                        silenceStart = now
                    }
                }
            } catch (t: Throwable) {
                QDLog.warn(logger, { "Capture loop terminated: ${t.message}" })
            } finally {
                try { line.stop() } catch (_: Throwable) {}
                try { line.flush() } catch (_: Throwable) {}
                try { line.close() } catch (_: Throwable) {}
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
            try { onStreamEnd?.invoke() } catch (_: Throwable) {}
            try { currentPipeOut?.close() } catch (_: Throwable) {}
            currentPipeOut = null
        }
        outputBuffer.reset()
        if (!inSilence) { inSilence = true }
        silenceStart = now
        try { onMuteChanged?.invoke(true) } catch (_: Throwable) {}
    }

    fun unmute() {
        if (!isMuted) return
        isMuted = false
        try { onMuteChanged?.invoke(false) } catch (_: Throwable) {}
    }

    fun stopCapture() {
        if (!isCapturing.compareAndSet(true, false)) return
        captureThread?.interrupt()
        try { captureThread?.join(1000) } catch (_: Throwable) {}
        captureThread = null
        outputBuffer.reset()
        try { currentPipeOut?.close() } catch (_: Throwable) {}
        currentPipeOut = null
        QDLog.info(logger) { "Capture stopped" }
    }

    companion object {
        private val logger = Logger.getInstance(AudioCapture::class.java)
        const val SILENCE_THRESHOLD: Int = 220
        const val SILENCE_DURATION_MS: Int = 350
        const val SPEECH_LENGHT_MIN_MS: Int = 1200
        const val SPEECH_PAUSE_DURATION_MIN_MS: Int = 900
        const val MAX_SPEECH_SEGMENT_MS: Int = 15000

        @JvmStatic
        fun main(args: Array<String>) {
            val capture = AudioCapture(
                fullBufferCallback = { /* WAV segment */ },
                onStreamStart = { /* input stream */ },
                onStreamBytes = { bytes, _ -> /* optional, for debug */ },
                onStreamEnd = { /* close streaming */ },
                onMuteChanged = { /* update UI */ }
            )
            thread { capture.startCapture(onSilence = {}, onSpeech = {}) }
            Thread.sleep(60000)
            capture.stopCapture()
        }
    }
}
