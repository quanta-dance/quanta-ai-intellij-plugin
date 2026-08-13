// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.system

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedWriter
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

internal enum class TerminalCommandAction {
    RUN,
    STATUS,
    READ,
    WAIT,
    CANCEL,
    LIST,
}

internal enum class TerminalCommandMode {
    AUTO,
    FOREGROUND,
    BACKGROUND,
}

internal enum class TerminalOutputStream {
    COMBINED,
    STDOUT,
    STDERR,
}

internal enum class TerminalReadPosition {
    TAIL,
    HEAD,
}

internal enum class TerminalJobState {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELED,
    TIMED_OUT,
}

internal data class TerminalJobSnapshot(
    val jobId: String,
    val command: String,
    val state: TerminalJobState,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val exitCode: Int?,
    val durationMs: Long,
    val workingDirectory: String?,
    val stdoutPath: String,
    val stderrPath: String,
    val combinedPath: String,
    val stdoutBytes: Long,
    val stderrBytes: Long,
    val combinedBytes: Long,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val combinedTruncated: Boolean,
)

internal data class TerminalReadResult(
    val job: TerminalJobSnapshot,
    val stream: TerminalOutputStream,
    val position: TerminalReadPosition,
    val text: String,
    val textTruncatedForReturn: Boolean,
)

internal class TerminalCommandJobManager(
    private val baseDirectory: Path?,
    private val tempRoot: Path,
    private val consoleSink: (String, Boolean) -> Unit = { _, _ -> },
    private val clock: Clock = Clock.systemUTC(),
    private val hardTimeout: Duration = Duration.ofHours(1),
    private val maxLogBytesPerStream: Long = 8L * 1024 * 1024,
    private val loginShellPathProvider: (() -> String?)? = null,
) : Closeable {
    private val logger = Logger.getInstance(TerminalCommandJobManager::class.java)
    private val jobs = ConcurrentHashMap<String, TerminalJob>()
    private val threadCounter = AtomicInteger(1)
    private val executor =
        Executors.newCachedThreadPool(
            ThreadFactory { runnable ->
                Thread(runnable, "quanta-terminal-job-${threadCounter.getAndIncrement()}").apply {
                    isDaemon = true
                }
            },
        )

    init {
        Files.createDirectories(tempRoot)
    }

    fun startCommand(
        command: String,
        env: Map<String, String>,
        consoleSinkOverride: (String, Boolean) -> Unit = consoleSink,
    ): TerminalJobSnapshot {
        val jobId = UUID.randomUUID().toString()
        val jobDir = Files.createDirectories(tempRoot.resolve(jobId))
        val stdoutCapture = BoundedLogFile(jobDir.resolve("stdout.log"), maxLogBytesPerStream)
        val stderrCapture = BoundedLogFile(jobDir.resolve("stderr.log"), maxLogBytesPerStream)
        val combinedCapture = BoundedLogFile(jobDir.resolve("combined.log"), maxLogBytesPerStream)
        val processBuilder = shellProcessBuilder(command)
        if (baseDirectory != null) {
            processBuilder.directory(baseDirectory.toFile())
        }
        processBuilder.environment().putAll(prepareEnvironment(processBuilder.environment(), env))
        val process = processBuilder.start()
        val startedAt = clock.instant()
        val job =
            TerminalJob(
                snapshot =
                    TerminalJobSnapshot(
                        jobId = jobId,
                        command = command,
                        state = TerminalJobState.RUNNING,
                        startedAt = startedAt,
                        finishedAt = null,
                        exitCode = null,
                        durationMs = 0,
                        workingDirectory = processBuilder.directory()?.absolutePath,
                        stdoutPath = stdoutCapture.path.toString(),
                        stderrPath = stderrCapture.path.toString(),
                        combinedPath = combinedCapture.path.toString(),
                        stdoutBytes = 0,
                        stderrBytes = 0,
                        combinedBytes = 0,
                        stdoutTruncated = false,
                        stderrTruncated = false,
                        combinedTruncated = false,
                    ),
                process = process,
                stdoutCapture = stdoutCapture,
                stderrCapture = stderrCapture,
                combinedCapture = combinedCapture,
                consoleSink = consoleSinkOverride,
            )
        jobs[jobId] = job
        executor.submit { pumpStream(job, TerminalOutputStream.STDOUT) }
        executor.submit { pumpStream(job, TerminalOutputStream.STDERR) }
        executor.submit { monitorJob(job) }
        return snapshot(job)
    }

    fun listJobs(): List<TerminalJobSnapshot> =
        jobs.values
            .map { snapshot(it) }
            .sortedByDescending { it.startedAt }

    fun status(jobId: String): TerminalJobSnapshot = snapshot(requireJob(jobId))

    fun waitForJob(
        jobId: String,
        timeoutSeconds: Int,
    ): TerminalJobSnapshot {
        val job = requireJob(jobId)
        if (job.process.isAlive) {
            job.process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        }
        return snapshot(job)
    }

    fun cancelJob(jobId: String): TerminalJobSnapshot {
        val job = requireJob(jobId)
        job.cancelRequested.set(true)
        destroyProcessTree(job.process)
        return snapshot(job)
    }

    fun readOutput(
        jobId: String,
        stream: TerminalOutputStream,
        position: TerminalReadPosition,
        maxChars: Int,
    ): TerminalReadResult {
        val job = requireJob(jobId)
        val capture =
            when (stream) {
                TerminalOutputStream.COMBINED -> job.combinedCapture
                TerminalOutputStream.STDOUT -> job.stdoutCapture
                TerminalOutputStream.STDERR -> job.stderrCapture
            }
        val clampedMaxChars = maxChars.coerceIn(200, 20_000)
        val text =
            when (position) {
                TerminalReadPosition.HEAD -> capture.readHead(clampedMaxChars)
                TerminalReadPosition.TAIL -> capture.readTail(clampedMaxChars)
            }
        val textTruncatedForReturn = capture.sizeBytes > clampedMaxChars.toLong()
        return TerminalReadResult(
            job = snapshot(job),
            stream = stream,
            position = position,
            text = text,
            textTruncatedForReturn = textTruncatedForReturn,
        )
    }

    override fun close() {
        jobs.values.forEach { job ->
            if (job.process.isAlive) {
                job.cancelRequested.set(true)
                destroyProcessTree(job.process)
            }
            job.close()
        }
        executor.shutdownNow()
    }

    internal fun prepareEnvironment(
        inheritedEnv: Map<String, String>,
        requestedEnv: Map<String, String>,
    ): Map<String, String> {
        val merged = LinkedHashMap(inheritedEnv)
        merged.putAll(requestedEnv)
        if (!isWindows()) {
            val resolvedPath =
                mergePathValues(
                    inheritedEnv["PATH"],
                    (loginShellPathProvider ?: { resolveLoginShellPath(baseDirectory) }).invoke(),
                    requestedEnv["PATH"],
                )
            if (!resolvedPath.isNullOrBlank()) {
                merged["PATH"] = resolvedPath
            }
        }
        return merged
    }

    internal fun mergePathValues(vararg pathValues: String?): String? {
        val separator = if (isWindows()) ';' else ':'
        val entries = LinkedHashSet<String>()
        pathValues.forEach { value ->
            value
                ?.split(separator)
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.forEach(entries::add)
        }
        return entries.joinToString(separator.toString()).ifBlank { null }
    }

    private fun monitorJob(job: TerminalJob) {
        try {
            val finished = job.process.waitFor(hardTimeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!finished) {
                job.timedOut.set(true)
                destroyProcessTree(job.process)
            }
            val exitCode = runCatching { job.process.exitValue() }.getOrNull()
            updateSnapshot(job, exitCode)
        } catch (t: Throwable) {
            logger.warn("Terminal job monitor failed for ${job.snapshot.jobId}", t)
            updateSnapshot(job, runCatching { job.process.exitValue() }.getOrNull())
        }
    }

    private fun pumpStream(
        job: TerminalJob,
        stream: TerminalOutputStream,
    ) {
        val input =
            when (stream) {
                TerminalOutputStream.STDOUT -> job.process.inputStream
                TerminalOutputStream.STDERR -> job.process.errorStream
                TerminalOutputStream.COMBINED -> return
            }
        val capture =
            when (stream) {
                TerminalOutputStream.STDOUT -> job.stdoutCapture
                TerminalOutputStream.STDERR -> job.stderrCapture
                TerminalOutputStream.COMBINED -> job.combinedCapture
            }
        input.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count <= 0) break
                val text = String(buffer, 0, count)
                capture.append(text)
                job.combinedCapture.append(text)
                job.consoleSink(text, stream == TerminalOutputStream.STDERR)
            }
        }
    }

    private fun snapshot(job: TerminalJob): TerminalJobSnapshot {
        updateSnapshot(job, runCatching { job.process.exitValue() }.getOrNull())
        return job.snapshot.copy(
            stdoutBytes = job.stdoutCapture.sizeBytes,
            stderrBytes = job.stderrCapture.sizeBytes,
            combinedBytes = job.combinedCapture.sizeBytes,
            stdoutTruncated = job.stdoutCapture.truncated,
            stderrTruncated = job.stderrCapture.truncated,
            combinedTruncated = job.combinedCapture.truncated,
            durationMs = durationMillis(job.snapshot.startedAt, job.snapshot.finishedAt),
        )
    }

    @Synchronized
    private fun updateSnapshot(
        job: TerminalJob,
        exitCode: Int?,
    ) {
        if (job.snapshot.state != TerminalJobState.RUNNING) {
            job.snapshot =
                job.snapshot.copy(
                    stdoutBytes = job.stdoutCapture.sizeBytes,
                    stderrBytes = job.stderrCapture.sizeBytes,
                    combinedBytes = job.combinedCapture.sizeBytes,
                    stdoutTruncated = job.stdoutCapture.truncated,
                    stderrTruncated = job.stderrCapture.truncated,
                    combinedTruncated = job.combinedCapture.truncated,
                    durationMs = durationMillis(job.snapshot.startedAt, job.snapshot.finishedAt),
                )
            return
        }
        if (job.process.isAlive && !job.timedOut.get()) {
            job.snapshot =
                job.snapshot.copy(
                    stdoutBytes = job.stdoutCapture.sizeBytes,
                    stderrBytes = job.stderrCapture.sizeBytes,
                    combinedBytes = job.combinedCapture.sizeBytes,
                    stdoutTruncated = job.stdoutCapture.truncated,
                    stderrTruncated = job.stderrCapture.truncated,
                    combinedTruncated = job.combinedCapture.truncated,
                    durationMs = durationMillis(job.snapshot.startedAt, null),
                )
            return
        }
        val finishedAt = clock.instant()
        val state =
            when {
                job.timedOut.get() -> TerminalJobState.TIMED_OUT
                job.cancelRequested.get() -> TerminalJobState.CANCELED
                exitCode == null -> TerminalJobState.FAILED
                exitCode == 0 -> TerminalJobState.COMPLETED
                else -> TerminalJobState.FAILED
            }
        job.snapshot =
            job.snapshot.copy(
                state = state,
                finishedAt = finishedAt,
                exitCode = exitCode,
                stdoutBytes = job.stdoutCapture.sizeBytes,
                stderrBytes = job.stderrCapture.sizeBytes,
                combinedBytes = job.combinedCapture.sizeBytes,
                stdoutTruncated = job.stdoutCapture.truncated,
                stderrTruncated = job.stderrCapture.truncated,
                combinedTruncated = job.combinedCapture.truncated,
                durationMs = durationMillis(job.snapshot.startedAt, finishedAt),
            )
    }

    private fun requireJob(jobId: String): TerminalJob = jobs[jobId] ?: throw IllegalArgumentException("Terminal job not found: $jobId")

    private fun shellProcessBuilder(command: String): ProcessBuilder =
        if (isWindows()) {
            ProcessBuilder("cmd", "/c", command)
        } else {
            ProcessBuilder("/bin/sh", "-c", command)
        }

    private fun destroyProcessTree(process: Process) {
        val descendants =
            process
                .toHandle()
                .descendants()
                .toList()
                .reversed()
        descendants.forEach { handle ->
            runCatching { handle.destroy() }
        }
        runCatching { process.destroy() }
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            descendants.forEach { handle ->
                runCatching { handle.destroyForcibly() }
            }
            runCatching { process.destroyForcibly() }
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }

    private fun durationMillis(
        startedAt: Instant,
        finishedAt: Instant?,
    ): Long = Duration.between(startedAt, finishedAt ?: clock.instant()).toMillis().coerceAtLeast(0)

    private fun isWindows(): Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("win")
}

private fun resolveLoginShellPath(baseDirectory: Path?): String? {
    val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
    return runCatching {
        ProcessBuilder(shell, "-lc", "printf %s \"\$PATH\"")
            .directory(baseDirectory?.toFile())
            .redirectErrorStream(true)
            .start()
            .let { process ->
                val output =
                    process.inputStream
                        .bufferedReader(StandardCharsets.UTF_8)
                        .use { it.readText() }
                        .trim()
                process.waitFor(5, TimeUnit.SECONDS)
                if (process.exitValue() == 0) output.takeIf { it.isNotBlank() } else null
            }
    }.getOrNull()
}

private class TerminalJob(
    var snapshot: TerminalJobSnapshot,
    val process: Process,
    val stdoutCapture: BoundedLogFile,
    val stderrCapture: BoundedLogFile,
    val combinedCapture: BoundedLogFile,
    val consoleSink: (String, Boolean) -> Unit,
) : Closeable {
    val cancelRequested = AtomicBoolean(false)
    val timedOut = AtomicBoolean(false)

    override fun close() {
        stdoutCapture.close()
        stderrCapture.close()
        combinedCapture.close()
    }
}

internal class BoundedLogFile(
    val path: Path,
    private val maxBytes: Long,
) : Closeable {
    private val lock = Any()
    private val writer: BufferedWriter =
        Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    private var truncatedMarkerWritten = false

    @Volatile
    var truncated: Boolean = false
        private set

    @Volatile
    var sizeBytes: Long = 0
        private set

    fun append(text: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            if (truncatedMarkerWritten) return
            val remainingBytes = max(0L, maxBytes - sizeBytes)
            if (remainingBytes == 0L) {
                appendMarkerLocked()
                return
            }
            val chunk = fitToBytes(text, remainingBytes)
            if (chunk.isNotEmpty()) {
                writer.write(chunk)
                sizeBytes += chunk.toByteArray(StandardCharsets.UTF_8).size
            }
            if (chunk.length < text.length || sizeBytes >= maxBytes) {
                appendMarkerLocked()
            }
            writer.flush()
        }
    }

    fun readHead(maxChars: Int): String =
        synchronized(lock) {
            writer.flush()
            val text = Files.readString(path, StandardCharsets.UTF_8)
            if (text.length <= maxChars) text else text.take(maxChars)
        }

    fun readTail(maxChars: Int): String =
        synchronized(lock) {
            writer.flush()
            val text = Files.readString(path, StandardCharsets.UTF_8)
            if (text.length <= maxChars) text else text.takeLast(maxChars)
        }

    override fun close() {
        synchronized(lock) {
            writer.flush()
            writer.close()
        }
    }

    private fun appendMarkerLocked() {
        if (truncatedMarkerWritten) return
        val marker = "\n[terminal output truncated after $maxBytes bytes]\n"
        val markerChunk = fitToBytes(marker, max(0L, maxBytes - sizeBytes))
        if (markerChunk.isNotEmpty()) {
            writer.write(markerChunk)
            sizeBytes += markerChunk.toByteArray(StandardCharsets.UTF_8).size
        }
        writer.flush()
        truncated = true
        truncatedMarkerWritten = true
    }

    private fun fitToBytes(
        text: String,
        maxBytes: Long,
    ): String {
        if (maxBytes <= 0) return ""
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        var low = 0
        var high = text.length
        var best = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = text.substring(0, mid)
            val candidateBytes = candidate.toByteArray(StandardCharsets.UTF_8).size.toLong()
            if (candidateBytes <= maxBytes) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return text.substring(0, best)
    }
}
