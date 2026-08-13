// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.builder

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Backend tool for running Gradle build/compile tasks through the project wrapper.
 *
 * Compared with [RunGradleTestsTool], this tool is intended for compilation and general build
 * verification rather than parsing test results from XML reports.
 */
@JsonClassDescription("Run Gradle compile tasks and return result summary with optional stdout tail.")
class RunGradleBuildTool : ToolInterface<String> {
    @field:JsonPropertyDescription("Gradle task names to run, space-separated. Default: 'compileKotlin compileJava'")
    var tasks: String? = null

    @field:JsonPropertyDescription("How many lines of stdout tail to include in the result (0 = none). Default: 50")
    var stdoutTailLines: Int = 50

    override fun execute(project: Project): String {
        val basePath = project.basePath ?: return "Project base path not found"
        val tasksList =
            (tasks?.trim()?.takeIf { it.isNotEmpty() } ?: "compileKotlin compileJava")
                .split(" ")
                .filter { it.isNotBlank() }

        val gradlewName = if (SystemInfo.isWindows) "gradlew.bat" else "gradlew"
        val gradlew = File(basePath, gradlewName)
        if (!gradlew.exists()) return "Gradle wrapper not found: $gradlewName"

        val args = mutableListOf<String>()
        args += gradlew.absolutePath
        args += tasksList
        // Prefer warnings visible
        args += listOf("--warning-mode", "all", "--stacktrace")

        val proc =
            try {
                ProcessBuilder(args).directory(File(basePath)).redirectErrorStream(true).start()
            } catch (e: Exception) {
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
            return "Gradle build timed out"
        }
        val exit = proc.exitValue()
        val tail = if (stdoutTailLines > 0) output.lines().takeLast(stdoutTailLines).joinToString("\n") else null
        val summary = if (exit == 0) "Build succeeded" else "Build failed (exit=$exit)"
        return if (tail != null) "$summary\n$tail" else summary
    }
}
