// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.system

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendRuntimeSettingsService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolExecutionPresentation
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolPresentationProvider
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

/**
 * Tool for running terminal commands within the IDE.
 */
@JsonClassDescription("Execute commands safely in managed foreground/background terminal jobs.")
class TerminalCommandTool :
    ToolInterface<Map<String, Any>>,
    ToolPresentationProvider {
    @field:JsonPropertyDescription("Action: RUN | STATUS | READ | WAIT | CANCEL | LIST. Default: RUN")
    var action: String? = null

    @field:JsonPropertyDescription("Command to execute for RUN, e.g. echo 'hello world'")
    var command: String? = null

    @field:JsonPropertyDescription("Execution mode for RUN: AUTO | FOREGROUND | BACKGROUND. Default: AUTO")
    var mode: String? = null

    @field:JsonPropertyDescription("Existing terminal job id for STATUS, READ, WAIT, or CANCEL")
    var jobId: String? = null

    @field:JsonPropertyDescription(
        "Wait timeout in seconds for WAIT or RUN foreground/auto polling. Default: 4 for AUTO, 15 for FOREGROUND, 0 for BACKGROUND",
    )
    var waitTimeoutSeconds: Int? = null

    @field:JsonPropertyDescription("Output stream to inspect for READ: COMBINED | STDOUT | STDERR. Default: COMBINED")
    var stream: String? = null

    @field:JsonPropertyDescription("READ position: TAIL | HEAD. Default: TAIL")
    var position: String? = null

    @field:JsonPropertyDescription("Maximum characters to return inline for READ or previews. Default: 4000")
    var maxOutputChars: Int? = null

    @JsonProperty("envVars")
    @field:JsonPropertyDescription("Environment variables as list entries, e.g. [{\"name\":\"KEY\",\"value\":\"VALUE\"}]")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    var envVars: MutableList<EnvVarEntry> = mutableListOf()

    companion object {
        @Volatile
        private var consoleView: ConsoleView? = null

        private val logger = Logger.getInstance(TerminalCommandTool::class.java)
        private const val DEFAULT_AUTO_WAIT_SECONDS = 4
        private const val DEFAULT_FOREGROUND_WAIT_SECONDS = 15
        private const val DEFAULT_READ_CHARS = 4_000
        private const val SMALL_INLINE_OUTPUT_LIMIT = 3_000
    }

    override fun presentation(status: ToolExecutionStatus): ToolExecutionPresentation {
        val title = "Terminal Command Tool"
        val detail =
            when (parseAction(action)) {
                TerminalCommandAction.RUN -> {
                    command?.trim()?.takeIf { it.isNotBlank() }?.let { "Command: $it" }
                }

                TerminalCommandAction.STATUS,
                TerminalCommandAction.WAIT,
                TerminalCommandAction.CANCEL,
                TerminalCommandAction.READ,
                TerminalCommandAction.LIST,
                -> {
                    jobId?.trim()?.takeIf { it.isNotBlank() }?.let { "Job: $it" }
                }
            }
        return ToolExecutionPresentation(title = title, detail = detail)
    }

    override fun execute(project: Project): Map<String, Any> {
        val terminalAction = parseAction(action)
        val manager = project.service<TerminalCommandJobService>().manager()
        return when (terminalAction) {
            TerminalCommandAction.RUN -> runCommand(project, manager)
            TerminalCommandAction.STATUS -> statusJob(manager)
            TerminalCommandAction.READ -> readJobOutput(manager)
            TerminalCommandAction.WAIT -> waitForJob(manager)
            TerminalCommandAction.CANCEL -> cancelJob(manager)
            TerminalCommandAction.LIST -> listJobs(manager)
        }
    }

    private fun runCommand(
        project: Project,
        manager: TerminalCommandJobManager,
    ): Map<String, Any> {
        val cmd = command?.trim().orEmpty()
        if (cmd.isEmpty()) {
            return errorResult("Command is not specified.")
        }
        validateAllowedCommand(cmd)?.let { return errorResult(it) }

        val envMap =
            envVars
                .filter { it.name.isNotBlank() }
                .associate { it.name to (it.value ?: "") }

        QDLog.info(logger) { "Executing managed terminal command: $cmd" }
        ensureConsole(project)
        appendToConsole("> $cmd\n", isError = false)

        val snapshot =
            manager.startCommand(cmd, envMap) { text, isError ->
                appendToConsole(text, isError)
            }
        val executionMode = parseMode(mode)
        val waitSeconds =
            waitTimeoutSeconds ?: when (executionMode) {
                TerminalCommandMode.AUTO -> DEFAULT_AUTO_WAIT_SECONDS
                TerminalCommandMode.FOREGROUND -> DEFAULT_FOREGROUND_WAIT_SECONDS
                TerminalCommandMode.BACKGROUND -> 0
            }
        val settledSnapshot =
            if (waitSeconds > 0) {
                manager.waitForJob(snapshot.jobId, waitSeconds)
            } else {
                snapshot
            }
        val preview =
            manager.readOutput(
                jobId = settledSnapshot.jobId,
                stream = TerminalOutputStream.COMBINED,
                position = TerminalReadPosition.TAIL,
                maxChars = effectiveReadChars(),
            )
        val isRunning = settledSnapshot.state == TerminalJobState.RUNNING
        val inlineText =
            if (!isRunning && settledSnapshot.combinedBytes <= SMALL_INLINE_OUTPUT_LIMIT) {
                preview.text.ifBlank { "Command completed with no output." }
            } else {
                buildRunSummary(settledSnapshot, executionMode)
            }

        val displaySummary = "Terminal Command Tool: ${summarizeCommand(settledSnapshot.command)}"
        return baseResult(settledSnapshot) +
            mapOf(
                "action" to "run",
                "mode" to executionMode.name.lowercase(),
                "waitedSeconds" to waitSeconds,
                "running" to isRunning,
                "text" to inlineText,
                "displaySummary" to displaySummary,
                "outputPreview" to preview.text,
                "outputPreviewPosition" to preview.position.name.lowercase(),
                "outputPreviewChars" to preview.text.length,
                "outputPreviewTruncatedForReturn" to preview.textTruncatedForReturn,
                "nextSuggestedAction" to
                    if (isRunning) {
                        "WAIT or READ"
                    } else {
                        "READ if you need more output"
                    },
            )
    }

    private fun statusJob(manager: TerminalCommandJobManager): Map<String, Any> {
        val snapshot = manager.status(requireJobId())
        val preview =
            manager.readOutput(
                jobId = snapshot.jobId,
                stream = TerminalOutputStream.COMBINED,
                position = TerminalReadPosition.TAIL,
                maxChars = effectiveReadChars(),
            )
        val summary = buildStatusSummary(snapshot)
        val displaySummary = "Terminal Command Tool: ${summarizeCommand(snapshot.command)}"
        return baseResult(snapshot) +
            mapOf(
                "action" to "status",
                "running" to (snapshot.state == TerminalJobState.RUNNING),
                "text" to summary,
                "displaySummary" to displaySummary,
                "outputPreview" to preview.text,
                "outputPreviewPosition" to "tail",
                "outputPreviewChars" to preview.text.length,
                "outputPreviewTruncatedForReturn" to preview.textTruncatedForReturn,
            )
    }

    private fun readJobOutput(manager: TerminalCommandJobManager): Map<String, Any> {
        val readResult =
            manager.readOutput(
                jobId = requireJobId(),
                stream = parseStream(stream),
                position = parsePosition(position),
                maxChars = effectiveReadChars(),
            )
        val summary =
            "Read ${readResult.stream.name.lowercase()} ${readResult.position.name.lowercase()} output for terminal job ${readResult.job.jobId}."
        val displaySummary = "Terminal Command Tool: ${summarizeCommand(readResult.job.command)}"
        return baseResult(readResult.job) +
            mapOf(
                "action" to "read",
                "stream" to readResult.stream.name.lowercase(),
                "position" to readResult.position.name.lowercase(),
                "text" to readResult.text,
                "displaySummary" to displaySummary,
                "outputChars" to readResult.text.length,
                "outputTruncatedForReturn" to readResult.textTruncatedForReturn,
                "summary" to summary,
            )
    }

    private fun waitForJob(manager: TerminalCommandJobManager): Map<String, Any> {
        val timeout = (waitTimeoutSeconds ?: DEFAULT_AUTO_WAIT_SECONDS).coerceIn(1, 300)
        val snapshot = manager.waitForJob(requireJobId(), timeout)
        val preview =
            manager.readOutput(
                jobId = snapshot.jobId,
                stream = TerminalOutputStream.COMBINED,
                position = TerminalReadPosition.TAIL,
                maxChars = effectiveReadChars(),
            )
        val summary =
            if (snapshot.state == TerminalJobState.RUNNING) {
                "Terminal job ${snapshot.jobId} is still running after waiting $timeout seconds."
            } else {
                "Terminal job ${snapshot.jobId} finished with state ${snapshot.state.name.lowercase()}."
            }
        val displaySummary = "Terminal Command Tool: ${summarizeCommand(snapshot.command)}"
        return baseResult(snapshot) +
            mapOf(
                "action" to "wait",
                "waitedSeconds" to timeout,
                "running" to (snapshot.state == TerminalJobState.RUNNING),
                "text" to summary,
                "displaySummary" to displaySummary,
                "outputPreview" to preview.text,
                "outputPreviewPosition" to "tail",
                "outputPreviewChars" to preview.text.length,
                "outputPreviewTruncatedForReturn" to preview.textTruncatedForReturn,
            )
    }

    private fun cancelJob(manager: TerminalCommandJobManager): Map<String, Any> {
        val snapshot = manager.cancelJob(requireJobId())
        val summary = "Cancellation requested for terminal job ${snapshot.jobId}."
        val displaySummary = "Terminal Command Tool: ${summarizeCommand(snapshot.command)}"
        return baseResult(snapshot) +
            mapOf(
                "action" to "cancel",
                "running" to (snapshot.state == TerminalJobState.RUNNING),
                "text" to summary,
                "displaySummary" to displaySummary,
            )
    }

    private fun listJobs(manager: TerminalCommandJobManager): Map<String, Any> {
        val jobs =
            manager.listJobs().map { snapshot ->
                mapOf(
                    "jobId" to snapshot.jobId,
                    "command" to snapshot.command,
                    "state" to snapshot.state.name.lowercase(),
                    "durationMs" to snapshot.durationMs,
                    "exitCode" to snapshot.exitCode,
                    "startedAt" to snapshot.startedAt.toString(),
                )
            }
        val summary = "Found ${jobs.size} terminal job(s)."
        return mapOf(
            "action" to "list",
            "jobs" to jobs,
            "text" to summary,
            "displaySummary" to "Terminal Command Tool",
            "summary" to summary,
        )
    }

    private fun baseResult(snapshot: TerminalJobSnapshot): Map<String, Any> =
        buildMap {
            put("jobId", snapshot.jobId)
            put("command", snapshot.command)
            put("status", snapshot.state.name.lowercase())
            put("startedAt", snapshot.startedAt.toString())
            snapshot.finishedAt?.toString()?.let { put("finishedAt", it) }
            snapshot.exitCode?.let { put("exitCode", it) }
            snapshot.workingDirectory?.let { put("workingDirectory", it) }
            put("durationMs", snapshot.durationMs)
            put(
                "logFiles",
                mapOf(
                    "combined" to snapshot.combinedPath,
                    "stdout" to snapshot.stdoutPath,
                    "stderr" to snapshot.stderrPath,
                ),
            )
            put(
                "outputBytes",
                mapOf(
                    "combined" to snapshot.combinedBytes,
                    "stdout" to snapshot.stdoutBytes,
                    "stderr" to snapshot.stderrBytes,
                ),
            )
            put(
                "outputTruncated",
                mapOf(
                    "combined" to snapshot.combinedTruncated,
                    "stdout" to snapshot.stdoutTruncated,
                    "stderr" to snapshot.stderrTruncated,
                ),
            )
        }

    private fun summarizeCommand(command: String): String = command.trim().replace(Regex("\\s+"), " ").take(160)

    private fun buildRunSummary(
        snapshot: TerminalJobSnapshot,
        mode: TerminalCommandMode,
    ): String =
        when (snapshot.state) {
            TerminalJobState.RUNNING -> {
                "Terminal job ${snapshot.jobId} is still running in ${mode.name.lowercase()} mode. Use WAIT to poll or READ to inspect output without returning the full log."
            }

            TerminalJobState.COMPLETED -> {
                "Terminal job ${snapshot.jobId} completed successfully. Use READ if you need more output than the preview."
            }

            TerminalJobState.FAILED -> {
                "Terminal job ${snapshot.jobId} failed with exit code ${snapshot.exitCode}. Use READ to inspect full captured output."
            }

            TerminalJobState.CANCELED -> {
                "Terminal job ${snapshot.jobId} was canceled."
            }

            TerminalJobState.TIMED_OUT -> {
                "Terminal job ${snapshot.jobId} exceeded the hard timeout and was terminated."
            }
        }

    private fun buildStatusSummary(snapshot: TerminalJobSnapshot): String =
        when (snapshot.state) {
            TerminalJobState.RUNNING -> "Terminal job ${snapshot.jobId} is running."
            TerminalJobState.COMPLETED -> "Terminal job ${snapshot.jobId} completed successfully."
            TerminalJobState.FAILED -> "Terminal job ${snapshot.jobId} failed with exit code ${snapshot.exitCode}."
            TerminalJobState.CANCELED -> "Terminal job ${snapshot.jobId} was canceled."
            TerminalJobState.TIMED_OUT -> "Terminal job ${snapshot.jobId} timed out and was terminated."
        }

    private fun errorResult(message: String): Map<String, Any> =
        mapOf(
            "status" to "error",
            "message" to message,
            "errorText" to message,
            "displaySummary" to "Terminal Command Tool",
            "text" to message,
            "summary" to "Terminal Command Tool",
        )

    private fun requireJobId(): String =
        jobId
            ?.trim()
            .orEmpty()
            .ifBlank {
                throw IllegalArgumentException("jobId is required for action ${parseAction(action).name}.")
            }

    private fun effectiveReadChars(): Int = (maxOutputChars ?: DEFAULT_READ_CHARS).coerceIn(200, 20_000)

    private fun parseAction(value: String?): TerminalCommandAction = parseEnum(value, TerminalCommandAction.RUN)

    private fun parseMode(value: String?): TerminalCommandMode = parseEnum(value, TerminalCommandMode.AUTO)

    private fun parseStream(value: String?): TerminalOutputStream = parseEnum(value, TerminalOutputStream.COMBINED)

    private fun parsePosition(value: String?): TerminalReadPosition = parseEnum(value, TerminalReadPosition.TAIL)

    private fun validateAllowedCommand(cmd: String): String? =
        try {
            val settings = BackendRuntimeSettingsService.instance.settings
            if (settings.terminalToolEnabled == true) {
                val allowedPrefixes =
                    settings.terminalAllowedCommandsCsv
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .map { it.split(Regex("\\s+")) }
                        .filter { it.isNotEmpty() }
                if (allowedPrefixes.isNotEmpty()) {
                    val cmdTokens = cmd.split(Regex("\\s+"))
                    val allowed =
                        allowedPrefixes.any { prefix ->
                            cmdTokens.size >= prefix.size &&
                                prefix.indices.all { index -> cmdTokens[index] == prefix[index] }
                        }
                    if (!allowed) {
                        val allowedText = allowedPrefixes.joinToString(", ") { it.joinToString(" ") }
                        val msg = "Command is not allowed: '$cmd'. Allowed prefixes: $allowedText"
                        QDLog.warn(logger) { msg }
                        msg
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }

    private fun <T : Enum<T>> parseEnum(
        value: String?,
        default: T,
    ): T {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() }?.uppercase() ?: return default
        return default.javaClass.enumConstants?.firstOrNull { it.name == normalized } ?: default
    }

    private fun ensureConsole(project: Project) {
        ApplicationManager.getApplication().invokeAndWait {
            val terminalToolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            terminalToolWindow?.show()
            val contentManager = terminalToolWindow?.contentManager ?: return@invokeAndWait
            val existingContent = contentManager.findContent("Quanta AI")
            if (existingContent == null || consoleView == null) {
                val contentFactory = ContentFactory.getInstance()
                consoleView =
                    TextConsoleBuilderFactory
                        .getInstance()
                        .createBuilder(project)
                        .apply { setViewer(true) }
                        .console
                val content = contentFactory.createContent(consoleView?.component, "Quanta AI", false)
                contentManager.addContent(content)
                contentManager.setSelectedContent(content)
            } else {
                contentManager.setSelectedContent(existingContent)
            }
        }
    }

    private fun appendToConsole(
        text: String,
        isError: Boolean,
    ) {
        val contentType =
            if (isError) {
                ConsoleViewContentType.ERROR_OUTPUT
            } else {
                ConsoleViewContentType.NORMAL_OUTPUT
            }
        ApplicationManager.getApplication().invokeLater {
            consoleView?.print(text, contentType)
        }
    }
}

@JsonClassDescription("Environment variable entry")
data class EnvVarEntry(
    @field:JsonPropertyDescription("Variable name")
    val name: String = "",
    @field:JsonPropertyDescription("Variable value")
    val value: String? = null,
)
