// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.system

import com.fasterxml.jackson.annotation.*
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.*
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import java.io.File
import java.io.IOException

/**
 * Tool for running terminal commands within the IDE.
 */
@JsonClassDescription("Execute command in the terminal. For example: echo \"Hello, World!\"")
class TerminalCommandTool : ToolInterface<String> {
    @field:JsonPropertyDescription("Command to execute, e.g. echo 'hello world'")
    var command: String? = null

    @JsonProperty("envVars")
    @field:JsonPropertyDescription("Environment variables as list entries, e.g. [{\"name\":\"KEY\",\"value\":\"VALUE\"}]")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    var envVars: MutableList<EnvVarEntry> = mutableListOf()

    companion object {
        private var consoleView: ConsoleView? = null
        private val logger = Logger.getInstance(TerminalCommandTool::class.java)
    }

    override fun execute(project: Project): String {
        val basePath = project.basePath
        val cmd = command?.trim().orEmpty()
        if (cmd.isEmpty()) return "Command is not specified."

        // Security: allow-list of command prefixes when terminal tool is enabled.
        // Each entry in terminalAllowedCommandsCsv may contain spaces (multi-token prefix), e.g. "git add", "git commit", "./gradlew".
        // The command is allowed only if its tokens start with one of these prefixes.
        try {
            val settings = BackendQuantaSettingsState.instance.settings
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
                            if (cmdTokens.size < prefix.size) return@any false
                            prefix.indices.all { i -> cmdTokens[i] == prefix[i] }
                        }
                    if (!allowed) {
                        val allowedText = allowedPrefixes.joinToString(", ") { it.joinToString(" ") }
                        val msg = "Command is not allowed: '$cmd'. Allowed prefixes: $allowedText"
                        QDLog.warn(logger) { msg }
                        return msg
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // Execute via the system shell to support arguments and quoting
        val commandLine =

            if (SystemInfo.isWindows) {
                GeneralCommandLine("cmd").withParameters("/c", cmd)
            } else {
                GeneralCommandLine("/bin/sh").withParameters("-c", cmd)
            }

        val envMap: Map<String, String> =
            envVars
                .filter { it.name.isNotBlank() }
                .associate { it.name to (it.value ?: "") }
        if (envMap.isNotEmpty()) {
            commandLine.withEnvironment(envMap)
        }

        QDLog.info(logger) { "Executing command via shell: $cmd" }

        project.service<ToolWindowService>().addToolingMessage("Execute terminal", cmd)

        if (!basePath.isNullOrEmpty()) {
            commandLine.setWorkDirectory(File(basePath))
        }

        ApplicationManager.getApplication().invokeAndWait {
            val terminalToolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            terminalToolWindow?.show()

            val contentManager = terminalToolWindow?.contentManager
            val existingContent = contentManager?.findContent("Quanta AI")

            if (existingContent == null) {
                val contentFactory = ContentFactory.getInstance()
                consoleView = ConsoleViewImpl(project, true)
                val content = contentFactory.createContent(consoleView?.component, "Quanta AI", false)
                contentManager?.addContent(content)
                contentManager?.setSelectedContent(content)
            } else {
                contentManager.setSelectedContent(existingContent)
            }
        }

        // Print the original command to the console UI
        consoleView?.print("> $cmd\n", ConsoleViewContentType.USER_INPUT)

        return try {
            val outputBuilder = StringBuilder()
            val processHandler: ProcessHandler = ProcessHandlerFactory.getInstance().createProcessHandler(commandLine)

            processHandler.addProcessListener(
                object : ProcessListener {
                    private var filteredEcho = false

                    override fun onTextAvailable(
                        event: ProcessEvent,
                        outputType: Key<*>,
                    ) {
                        // Ignore system service messages
                        if (outputType === ProcessOutputType.SYSTEM) return

                        val text = event.text.replace("\r", "")
                        val trimmed = text.trim()

                        // Filter an initial echo of the command (some shells or configurations may echo)
                        if (!filteredEcho && (trimmed == cmd || trimmed == commandLine.commandLineString)) {
                            filteredEcho = true
                            return
                        }

                        outputBuilder.append(text)
                        val contentType =
                            if (outputType === ProcessOutputType.STDERR) {
                                ConsoleViewContentType.ERROR_OUTPUT
                            } else {
                                ConsoleViewContentType.NORMAL_OUTPUT
                            }
                        consoleView?.print(text, contentType)
                    }

                    override fun startNotified(event: ProcessEvent) {}

                    override fun processTerminated(event: ProcessEvent) {}

                    override fun processWillTerminate(
                        event: ProcessEvent,
                        willBeDestroyed: Boolean,
                    ) {
                    }
                },
            )

            processHandler.startNotify()
            processHandler.waitFor()

            val exitCode = processHandler.exitCode
            if (exitCode != 0) {
                val errorMsg = "Command exited with code $exitCode\n"
                consoleView?.print(errorMsg, ConsoleViewContentType.ERROR_OUTPUT)
                outputBuilder.append(errorMsg)
                QDLog.warn(logger, { "Command '$cmd' exited with $exitCode" })
            }

            outputBuilder.toString().trim()
        } catch (e: IOException) {
            val errorMsg = "Error executing command: ${e.message}\n"
            consoleView?.print(errorMsg, ConsoleViewContentType.ERROR_OUTPUT)
            QDLog.error(logger, { "Error executing command" }, e)
            errorMsg
        } catch (e: ProcessNotCreatedException) {
            val errorMsg = "Process creation failed: ${e.message}\n"
            consoleView?.print(errorMsg, ConsoleViewContentType.ERROR_OUTPUT)
            QDLog.error(logger, { "Process creation failed" }, e)
            errorMsg
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
