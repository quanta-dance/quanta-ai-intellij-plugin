package com.github.quanta_dance.quanta.plugins.intellij.tools.go

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.LinkedList
import java.util.concurrent.TimeUnit

@JsonClassDescription("Run Go tests (go test) with optional -run filter and stream progress; auto-detects go module in project root")
class RunGoTestsTool : ToolInterface<RunGoTestsTool.Result> {
    data class Result(
        val success: Boolean,
        val totalPackages: Int,
        val failedPackages: Int,
        val stdoutTail: String?,
        val error: String?,
    )

    @field:JsonPropertyDescription("Go package pattern to test (default: './...')")
    var packages: String? = null

    @field:JsonPropertyDescription("-run filter (regexp) to select tests, e.g., 'TestFoo|TestBar'")
    var runRegex: String? = null

    @field:JsonPropertyDescription("Enable verbose output (-v) (default: false). Output is still bounded in returned stdout tail.")
    var verbose: Boolean = false

    @field:JsonPropertyDescription(
        "Working directory relative to the project root (default: project root). " +
            "If autoDetectModule=true and go.mod exists in project root, it will be used.",
    )
    var workingDir: String? = null

    @field:JsonPropertyDescription("Auto-detect go module by checking go.mod in project root (default: true)")
    var autoDetectModule: Boolean = true

    @field:JsonPropertyDescription("How many lines of stdout tail to include in the result (0 = none). Default: 50")
    var stdoutTailLines: Int = 50

    @field:JsonPropertyDescription("Timeout minutes for go test. Default: 30")
    var timeoutMinutes: Long = 30

    @field:JsonPropertyDescription(
        "Absolute path to go binary (optional). If omitted, we try GOROOT/bin/go, common locations, or '/usr/bin/env go'.",
    )
    var goBinary: String? = null

    private fun addMsg(
        project: Project,
        title: String,
        msg: String,
    ) {
        try {
            project.service<ToolWindowService>().addToolingMessage(title, msg)
        } catch (_: Throwable) {
        }
    }

    private fun resolveGoBinary(): String? {
        // 1) Explicit field
        val user = goBinary?.trim()?.takeIf { it.isNotEmpty() }
        if (user != null && File(user).canExecute()) return user
        // 2) GOROOT/bin/go
        val goroot = System.getenv("GOROOT")?.trim()?.takeIf { it.isNotEmpty() }
        if (goroot != null) {
            val p = File(goroot, "bin${File.separator}${if (SystemInfo.isWindows) "go.exe" else "go"}")
            if (p.canExecute()) return p.absolutePath
        }
        // 3) Common locations
        val candidates = mutableListOf<String>()
        if (SystemInfo.isMac) {
            candidates +=
                listOf(
                    "/opt/homebrew/bin/go",
                    "/usr/local/bin/go",
                    "/usr/local/go/bin/go",
                    "/usr/bin/go",
                )
        } else if (SystemInfo.isWindows) {
            candidates +=
                listOf(
                    "C:/Program Files/Go/bin/go.exe",
                    "C:/Go/bin/go.exe",
                )
        } else {
            candidates +=
                listOf(
                    "/usr/local/go/bin/go",
                    "/usr/local/bin/go",
                    "/usr/bin/go",
                    "/snap/bin/go",
                )
        }
        for (c in candidates) if (File(c).canExecute()) return c
        // 4) Try '/usr/bin/env go' to resolve from PATH
        return if (canInvoke(arrayOf("/usr/bin/env", "go", "version"))) "go" else null
    }

    private fun canInvoke(
        cmd: Array<String>,
        wd: File? = null,
    ): Boolean {
        return try {
            val p =
                ProcessBuilder(*cmd)
                    .directory(wd)
                    .redirectErrorStream(true)
                    .start()
            p.waitFor(3, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            false
        }
    }

    override fun execute(project: Project): Result {
        val basePath = project.basePath ?: return Result(false, 0, 0, null, "Project base path not found")
        val pkg = (packages?.trim()?.takeIf { it.isNotEmpty() } ?: "./...")

        // Auto-detect module root
        val workDir =
            run {
                val userWd = workingDir?.trim()?.takeIf { it.isNotEmpty() }
                if (!autoDetectModule) {
                    File(basePath, userWd ?: ".")
                } else {
                    val rootGoMod = File(basePath, "go.mod")
                    if (rootGoMod.exists()) File(basePath) else File(basePath, userWd ?: ".")
                }
            }

        val goPath = resolveGoBinary()
        if (goPath == null) {
            val hint =
                buildString {
                    append("Go binary not found. Set goBinary explicitly or ensure PATH includes 'go'.\n")
                    append("Tried GOROOT/bin/go and common locations. On macOS with Homebrew: /opt/homebrew/bin/go\n")
                }
            addMsg(project, "Run Go tests - failed", hint)
            return Result(false, 0, 0, null, hint.trim())
        }

        val args = mutableListOf<String>()
        args += goPath
        args += listOf("test", pkg)
        if (verbose) args += "-v"
        val run = runRegex?.trim()
        if (!run.isNullOrEmpty()) {
            args += listOf("-run", run)
        }

        val taskLine = "cmd=${args.joinToString(" ")}\nwd=${workDir.absolutePath}"
        val toolMsg =
            try {
                project.service<ToolWindowService>().startToolingMessage("Run Go tests", taskLine)
            } catch (_: Throwable) {
                null
            }
        val spinner =
            try {
                project.service<ToolWindowService>().startSpinner("Running go test…")
            } catch (_: Throwable) {
                null
            }

        val output = StringBuilder()
        val process =
            try {
                ProcessBuilder(args)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                spinner?.stopError("Failed to start")
                toolMsg?.setText("$taskLine\nFailed to start go test: ${e.message}")
                return Result(false, 0, 0, null, "Failed to start go test: ${e.message}")
            }

        // Streaming progress (bounded recent buffer)
        val recent = LinkedList<String>()
        val recentCapacity = 50
        var totalPkgs = 0
        var failedPkgs = 0
        var lastPulse = 0L
        val start = System.currentTimeMillis()

        fun pushRecent(line: String) {
            recent.add(line)
            if (recent.size > recentCapacity) recent.removeFirst()
        }

        fun pulse() {
            val now = System.currentTimeMillis()
            if (now - lastPulse >= 1500) {
                lastPulse = now
                val elapsed = (now - start) / 1000
                val body =
                    buildString {
                        append(taskLine)
                        append("\nElapsed: ").append(elapsed).append("s\n")
                        append("Packages: ").append(totalPkgs).append(", Failed: ").append(failedPkgs).append("\n")
                        if (recent.isNotEmpty()) append(recent.joinToString("\n"))
                    }
                toolMsg?.setText(body)
            }
        }

        process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val ln = line!!
                output.appendLine(ln)
                val t = ln.trim()
                when {
                    t.startsWith("ok\t") -> {
                        totalPkgs += 1
                        pushRecent(t)
                        pulse()
                    }

                    t.startsWith("FAIL\t") -> {
                        totalPkgs += 1
                        failedPkgs += 1
                        pushRecent(t)
                        pulse()
                    }

                    t.startsWith("?\t") -> {
                        totalPkgs += 1
                        pushRecent(t)
                        pulse()
                    }

                    t.startsWith("--- FAIL:") || t.startsWith("--- PASS:") || t.startsWith("=== RUN") -> {
                        pushRecent(t)
                        pulse()
                    }
                }
            }
        }

        val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            spinner?.stopError("Timeout")
            toolMsg?.setText("$taskLine\nTimed out after ${timeoutMinutes}m\nPackages: $totalPkgs, Failed: $failedPkgs")
            return Result(
                false,
                totalPkgs,
                failedPkgs,
                tail(output, stdoutTailLines),
                "go test timed out",
            )
        }
        val exitCode = process.exitValue()

        // Refresh VFS
        VfsUtil.markDirtyAndRefresh(true, true, true, workDir)

        val success = exitCode == 0
        val finalBody =
            buildString {
                append(taskLine)
                append("\nPackages: ").append(totalPkgs).append(", Failed: ").append(failedPkgs)
            }
        if (success) spinner?.stopSuccess() else spinner?.stopError("exit=$exitCode")
        toolMsg?.setText(finalBody)

        return Result(
            success,
            totalPkgs,
            failedPkgs,
            tail(output, stdoutTailLines),
            if (success) "" else "go test failed with exit code $exitCode",
        )
    }

    private fun tail(
        sb: StringBuilder,
        n: Int,
    ): String? {
        if (n <= 0) return null
        val lines = sb.toString().lines()
        val count = if (n > lines.size) lines.size else n
        return lines.takeLast(count).joinToString("\n")
    }
}