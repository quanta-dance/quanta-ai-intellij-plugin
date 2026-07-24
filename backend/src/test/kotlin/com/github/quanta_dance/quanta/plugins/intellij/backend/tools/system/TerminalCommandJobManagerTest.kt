// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.system

import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class TerminalCommandJobManagerTest {
    @Test
    fun completesShortCommandAndCapturesOutput() {
        withManager { manager ->
            val snapshot = manager.startCommand(shortEchoCommand(), emptyMap())
            val completed = manager.waitForJob(snapshot.jobId, 5)
            val output =
                manager.readOutput(
                    snapshot.jobId,
                    TerminalOutputStream.COMBINED,
                    TerminalReadPosition.TAIL,
                    1_000,
                )

            assertEquals(TerminalJobState.COMPLETED, completed.state)
            assertTrue(output.text.contains("hello-terminal"))
        }
    }

    @Test
    fun keepsLongRunningCommandManageableAndCancelable() {
        withManager { manager ->
            val snapshot = manager.startCommand(longSleepCommand(), emptyMap())
            val stillRunning = manager.waitForJob(snapshot.jobId, 1)
            assertEquals(TerminalJobState.RUNNING, stillRunning.state)

            val canceled = manager.cancelJob(snapshot.jobId)
            val settled = manager.waitForJob(snapshot.jobId, 5)

            assertTrue(canceled.state == TerminalJobState.RUNNING || canceled.state == TerminalJobState.CANCELED)
            assertEquals(TerminalJobState.CANCELED, settled.state)
        }
    }

    @Test
    fun truncatesHugeOutputInBoundedLogs() {
        withManager(maxLogBytesPerStream = 512) { manager ->
            val snapshot = manager.startCommand(numberedOutputCommand(5_000), emptyMap())
            val completed = manager.waitForJob(snapshot.jobId, 10)
            val output =
                manager.readOutput(
                    snapshot.jobId,
                    TerminalOutputStream.COMBINED,
                    TerminalReadPosition.TAIL,
                    4_000,
                )

            assertEquals(TerminalJobState.COMPLETED, completed.state)
            assertTrue(completed.combinedTruncated)
            assertTrue(completed.combinedBytes <= 512)
            assertTrue(output.text.isNotBlank())
            assertFalse(output.text.length > 4_000)
        }
    }

    @Test
    fun separatesStdoutAndStderrAndPreservesCombinedLog() {
        withManager { manager ->
            val snapshot = manager.startCommand(stdoutAndStderrCommand(), emptyMap())
            val completed = manager.waitForJob(snapshot.jobId, 5)
            val stdout =
                manager.readOutput(
                    snapshot.jobId,
                    TerminalOutputStream.STDOUT,
                    TerminalReadPosition.TAIL,
                    1_000,
                )
            val stderr =
                manager.readOutput(
                    snapshot.jobId,
                    TerminalOutputStream.STDERR,
                    TerminalReadPosition.TAIL,
                    1_000,
                )
            val combined =
                manager.readOutput(
                    snapshot.jobId,
                    TerminalOutputStream.COMBINED,
                    TerminalReadPosition.TAIL,
                    1_000,
                )

            assertEquals(TerminalJobState.COMPLETED, completed.state)
            assertTrue(stdout.text.contains("stdout-line"))
            assertFalse(stdout.text.contains("stderr-line"))
            assertTrue(stderr.text.contains("stderr-line"))
            assertFalse(stderr.text.contains("stdout-line"))
            assertTrue(combined.text.contains("stdout-line"))
            assertTrue(combined.text.contains("stderr-line"))
        }
    }

    @Test
    fun supportsHeadAndTailReadsForLargeLogs() {
        withManager(maxLogBytesPerStream = 64 * 1024) { manager ->
            val snapshot = manager.startCommand(numberedOutputCommand(200), emptyMap())
            val completed = manager.waitForJob(snapshot.jobId, 10)
            val head =
                manager.readOutput(
                    snapshot.jobId,
                    TerminalOutputStream.COMBINED,
                    TerminalReadPosition.HEAD,
                    120,
                )
            val tail =
                manager.readOutput(
                    snapshot.jobId,
                    TerminalOutputStream.COMBINED,
                    TerminalReadPosition.TAIL,
                    120,
                )

            assertEquals(TerminalJobState.COMPLETED, completed.state)
            assertTrue(head.text.contains("line-1"))
            assertTrue(head.textTruncatedForReturn)
            assertTrue(tail.text.contains("line-200"))
            assertTrue(tail.textTruncatedForReturn)
            assertNotEquals(head.text, tail.text)
        }
    }

    @Test
    fun timesOutCommandsThatExceedHardLimit() {
        withManager(hardTimeout = Duration.ofSeconds(1)) { manager ->
            val snapshot = manager.startCommand(longSleepCommand(), emptyMap())
            val settled = manager.waitForJob(snapshot.jobId, 5)

            assertEquals(TerminalJobState.TIMED_OUT, settled.state)
        }
    }

    @Test
    fun forwardsProcessOutputToConsoleSink() {
        val sinkEvents = mutableListOf<Pair<String, Boolean>>()
        withManager(consoleSink = { text, isError -> sinkEvents += text to isError }) { manager ->
            val snapshot = manager.startCommand(stdoutAndStderrCommand(), emptyMap())
            manager.waitForJob(snapshot.jobId, 5)
        }

        waitUntil {
            sinkEvents.any { (text, isError) -> !isError && text.contains("stdout-line") } &&
                sinkEvents.any { (text, isError) -> isError && text.contains("stderr-line") }
        }
        assertTrue(sinkEvents.any { (text, isError) -> !isError && text.contains("stdout-line") })
        assertTrue(sinkEvents.any { (text, isError) -> isError && text.contains("stderr-line") })
    }

    @Test
    fun listsBackgroundJobsWhileTheyAreRunning() {
        withManager { manager ->
            val snapshot = manager.startCommand(longSleepCommand(), emptyMap())
            try {
                val listed = manager.listJobs().firstOrNull { it.jobId == snapshot.jobId }
                assertTrue(listed != null)
                assertEquals(TerminalJobState.RUNNING, listed.state)
            } finally {
                manager.cancelJob(snapshot.jobId)
                manager.waitForJob(snapshot.jobId, 5)
            }
        }
    }

    @Test
    fun mergesInheritedAndLoginShellPathEntriesWithoutDroppingRequestedPath() {
        withManager(loginShellPathProvider = { "/opt/homebrew/bin:/usr/local/bin:/usr/bin" }) { manager ->
            val merged =
                manager.prepareEnvironment(
                    inheritedEnv = mapOf("PATH" to "/usr/bin:/bin"),
                    requestedEnv = mapOf("PATH" to "/custom/bin:/usr/local/bin"),
                )

            assertEquals(
                "/usr/bin:/bin:/opt/homebrew/bin:/usr/local/bin:/custom/bin",
                merged["PATH"],
            )
        }
    }

    private fun withManager(
        hardTimeout: Duration = Duration.ofSeconds(30),
        maxLogBytesPerStream: Long = 8L * 1024 * 1024,
        consoleSink: (String, Boolean) -> Unit = { _, _ -> },
        loginShellPathProvider: () -> String? = { System.getenv("PATH") },
        block: (TerminalCommandJobManager) -> Unit,
    ) {
        val tempRoot = Files.createTempDirectory("terminal-job-test-")
        val manager =
            TerminalCommandJobManager(
                baseDirectory = null,
                tempRoot = tempRoot,
                consoleSink = consoleSink,
                hardTimeout = hardTimeout,
                maxLogBytesPerStream = maxLogBytesPerStream,
                loginShellPathProvider = loginShellPathProvider,
            )
        try {
            block(manager)
        } finally {
            manager.close()
            tempRoot.deleteRecursively()
        }
    }

    private fun shortEchoCommand(): String =
        if (isWindows()) {
            "echo hello-terminal"
        } else {
            "printf 'hello-terminal\\n'"
        }

    private fun longSleepCommand(): String =
        if (isWindows()) {
            "powershell -Command \"Start-Sleep -Seconds 20\""
        } else {
            "sleep 20"
        }

    private fun largeOutputCommand(): String = numberedOutputCommand(800)

    private fun waitUntil(
        timeoutMillis: Long = 2_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(condition())
    }

    private fun numberedOutputCommand(lines: Int): String =
        if (isWindows()) {
            "powershell -Command \"1..$lines | ForEach-Object { Write-Output ('line-' + ${'$'}_) }\""
        } else {
            "i=1; while [ \$i -le $lines ]; do echo line-\$i; i=\$((i+1)); done"
        }

    private fun stdoutAndStderrCommand(): String =
        if (isWindows()) {
            "powershell -Command \"Write-Output 'stdout-line'; Write-Error 'stderr-line'\""
        } else {
            "printf 'stdout-line\\n'; printf 'stderr-line\\n' >&2"
        }

    private fun isWindows(): Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("win")
}
