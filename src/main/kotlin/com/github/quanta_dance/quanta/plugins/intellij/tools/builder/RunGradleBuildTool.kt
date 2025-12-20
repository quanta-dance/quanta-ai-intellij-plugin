// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.builder

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

@JsonClassDescription("Run Gradle compile tasks and return result summary with optional stdout tail.")
class RunGradleBuildTool : ToolInterface<String> {
    @field:JsonPropertyDescription("Gradle task names to run, space-separated. Default: 'compileKotlin compileJava'")
    var tasks: String? = null

    @field:JsonPropertyDescription("How many lines of stdout tail to include in the result (0 = none). Default: 50")
    var stdoutTailLines: Int = 50

    override fun execute(project: Project): String {
        val basePath = project.basePath ?: return "Project base path not found"
        val tasksList = (tasks?.trim()?.takeIf { it.isNotEmpty() } ?: "compileKotlin compileJava").split(" ").filter { it.isNotBlank() }

        val gradlewName = if (SystemInfo.isWindows) "gradlew.bat" else "gradlew"
        val gradlew = File(basePath, gradlewName)
        if (!gradlew.exists()) return "Gradle wrapper not found: $gradlewName"

        val args = mutableListOf<String>()
        args += gradlew.absolutePath
        args += tasksList
        // Prefer warnings visible
        args += listOf("--warning-mode", "all", "--stacktrace")

        val tool = try { project.service<ToolWindowService>().startToolingMessage("Gradle build", "tasks=${args.joinToString(" ")}") } catch (_: Throwable) { null }
        val proc =
            try { ProcessBuilder(args).directory(File(basePath)).redirectErrorStream(true).start() } catch (e: Exception) {
                tool?.setText("Failed to start gradle: ${e.message}")
                return "Failed to start gradle: ${e.message}"
            }

        val output = StringBuilder()
        proc.inputStream.bufferedReader(StandardCharsets.UTF_8).use { r ->
            var line: String?
            while (r.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
        }

        val finished = proc.waitFor(20, TimeUnit.MINUTES)
        if (!finished) {
            proc.destroyForcibly()
            tool?.setText("Timeout while running gradle build")
            return "Gradle build timed out"
        }
        val exit = proc.exitValue()
        val tail = if (stdoutTailLines > 0) output.lines().takeLast(stdoutTailLines).joinToString("\n") else null
        val summary = if (exit == 0) "Build succeeded" else "Build failed (exit=$exit)"
        tool?.setText(("tasks=${args.joinToString(" ")}" + "\n\n" + (tail ?: "")).trim())
        return if (tail != null) "$summary\n$tail" else summary
    }
}
