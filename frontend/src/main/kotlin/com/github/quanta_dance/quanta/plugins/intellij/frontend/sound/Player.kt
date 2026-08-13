// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.sound

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.FrontendLogLevel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javazoom.jl.player.Player as JLayerPlayer

object Player {
    private val logger = Logger.getInstance(Player::class.java)

    @Volatile
    private var currentThread: Thread? = null

    @Volatile
    private var currentPlayer: JLayerPlayer? = null

    @Volatile
    private var currentStream: InputStream? = null

    @Volatile
    private var currentLine: SourceDataLine? = null

    @Volatile
    private var currentPcmQueue: LinkedBlockingQueue<ByteArray?>? = null

    @Synchronized
    fun playMp3(
        audioData: InputStream,
        onFinished: (() -> Unit)? = null,
    ) {
        stop()

        val player = JLayerPlayer(audioData)
        currentPlayer = player
        currentStream = audioData

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                player.play()
            } catch (t: Throwable) {
                logger.warn("Playback failed: ${t.message}", t)
            } finally {
                cleanupMp3(player, audioData, onFinished)
            }
        }
    }

    @Synchronized
    fun startStreamingPcm(
        onFinished: (() -> Unit)? = null,
        onDebugLog: ((FrontendLogLevel, String) -> Unit)? = null,
    ): ((ByteArray, Boolean) -> Unit) {
        stop()

        val format = AudioFormat(24_000f, 16, 1, true, false)
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format)
        currentLine = line

        val queue = LinkedBlockingQueue<ByteArray?>()
        currentPcmQueue = queue

        val worker =
            Thread({
                var started = false
                var bufferedBytes = 0
                val prebuffer = ByteArrayOutputStream()
                try {
                    while (true) {
                        val chunk = queue.poll(200, TimeUnit.MILLISECONDS)
                        if (chunk == null) {
                            if (Thread.currentThread().isInterrupted) break
                            continue
                        }
                        if (chunk === PCM_END_MARKER) {
                            break
                        }

                        if (!started) {
                            prebuffer.write(chunk)
                            bufferedBytes += chunk.size
                            logger.info("Player.pcm prebuffer chunkBytes=${chunk.size} bufferedBytes=$bufferedBytes")
                            onDebugLog?.invoke(
                                FrontendLogLevel.DEBUG,
                                "Player.pcm prebuffer chunkBytes=${chunk.size} bufferedBytes=$bufferedBytes",
                            )
                            if (bufferedBytes >= PCM_PREBUFFER_BYTES) {
                                line.start()
                                started = true
                                val buffered = prebuffer.toByteArray()
                                logger.info("Player.pcm start playback bufferedBytes=${buffered.size}")
                                onDebugLog?.invoke(
                                    FrontendLogLevel.INFO,
                                    "Player.pcm start playback bufferedBytes=${buffered.size}",
                                )
                                if (buffered.isNotEmpty()) {
                                    line.write(buffered, 0, buffered.size)
                                }
                                prebuffer.reset()
                            }
                        } else {
                            logger.info("Player.pcm live write chunkBytes=${chunk.size}")
                            onDebugLog?.invoke(FrontendLogLevel.DEBUG, "Player.pcm live write chunkBytes=${chunk.size}")
                            line.write(chunk, 0, chunk.size)
                        }
                    }

                    if (!started && bufferedBytes > 0) {
                        line.start()
                        started = true
                        val buffered = prebuffer.toByteArray()
                        logger.info("Player.pcm start playback at end bufferedBytes=${buffered.size}")
                        onDebugLog?.invoke(
                            FrontendLogLevel.INFO,
                            "Player.pcm start playback at end bufferedBytes=${buffered.size}",
                        )
                        if (buffered.isNotEmpty()) {
                            line.write(buffered, 0, buffered.size)
                        }
                        prebuffer.reset()
                        while (true) {
                            val chunk = queue.poll() ?: break
                            if (chunk === PCM_END_MARKER) break
                            logger.info("Player.pcm drain-tail chunkBytes=${chunk.size}")
                            onDebugLog?.invoke(FrontendLogLevel.DEBUG, "Player.pcm drain-tail chunkBytes=${chunk.size}")
                            line.write(chunk, 0, chunk.size)
                        }
                    }

                    if (started) {
                        try {
                            line.drain()
                        } catch (_: Throwable) {
                        }
                    }
                } catch (t: Throwable) {
                    logger.warn("PCM streaming playback failed: ${t.message}", t)
                    onDebugLog?.invoke(FrontendLogLevel.ERROR, "Player.pcm error ${t.message}")
                } finally {
                    synchronized(this) {
                        if (currentLine === line) {
                            try {
                                line.stop()
                            } catch (_: Throwable) {
                            }
                            try {
                                line.close()
                            } catch (_: Throwable) {
                            }
                            currentLine = null
                        }
                        if (currentPcmQueue === queue) {
                            currentPcmQueue = null
                        }
                        if (currentThread === Thread.currentThread()) {
                            currentThread = null
                        }
                    }
                    onFinished?.invoke()
                }
            }, "AI-PCM-Player")
        worker.isDaemon = true
        currentThread = worker
        worker.start()

        return { bytes, isLast ->
            val activeQueue = synchronized(this) { currentPcmQueue }
            if (activeQueue != null) {
                if (bytes.isNotEmpty()) {
                    activeQueue.offer(bytes)
                }
                if (isLast) {
                    activeQueue.offer(PCM_END_MARKER)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        try {
            currentPlayer?.close()
        } catch (_: Throwable) {
        }

        try {
            currentStream?.close()
        } catch (_: Throwable) {
        }

        try {
            currentPcmQueue?.offer(PCM_END_MARKER)
        } catch (_: Throwable) {
        }

        try {
            currentLine?.stop()
        } catch (_: Throwable) {
        }

        try {
            currentLine?.close()
        } catch (_: Throwable) {
        }

        try {
            currentThread?.interrupt()
        } catch (_: Throwable) {
        }

        currentPlayer = null
        currentThread = null
        currentStream = null
        currentLine = null
        currentPcmQueue = null
    }

    @Synchronized
    private fun cleanupMp3(
        player: JLayerPlayer,
        audioData: InputStream,
        onFinished: (() -> Unit)?,
    ) {
        try {
            if (currentPlayer === player) currentPlayer = null
            if (currentStream === audioData) currentStream = null
            onFinished?.invoke()
        } catch (_: Throwable) {
        }

        try {
            audioData.close()
        } catch (_: Throwable) {
        }
    }

    private val PCM_END_MARKER = ByteArray(0)
    private const val PCM_PREBUFFER_BYTES = 24_000
}
