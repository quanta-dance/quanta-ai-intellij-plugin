package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.models.RunTestsResult
import com.github.quanta_dance.quanta.plugins.intellij.tools.models.TestCaseResult
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

@JsonClassDescription("Run Gradle tests and collect failed tests from XML reports")
class RunGradleTestsTool : ToolInterface<RunTestsResult> {
    @JsonPropertyDescription("Gradle task names to run, space-separated. Default: 'test'")
    var tasks: String? = null

    @JsonPropertyDescription("Whether to run 'cleanTest' before tests to remove stale reports. Default: true")
    var cleanBefore: Boolean = true

    @JsonPropertyDescription("How many lines of stdout tail to include in the result (0 = none). Default: 50")
    var stdoutTailLines: Int = 50

    @JsonPropertyDescription("Path to XML reports directory relative to project root. Default: 'build/test-results/test'")
    var reportsDir: String? = null

    override fun execute(project: Project): RunTestsResult {
        val basePath = project.basePath ?: return RunTestsResult(false,0,0,0, emptyList(), null, "Project base path not found")
        val tasksList = (tasks?.trim()?.takeIf { it.isNotEmpty() } ?: "test").split(" ").filter { it.isNotBlank() }

        val gradlewName = if (SystemInfo.isWindows) "gradlew.bat" else "gradlew"
        val gradlew = File(basePath, gradlewName)
        if (!gradlew.exists()) {
            return RunTestsResult(false,0,0,0, emptyList(), null, "Gradle wrapper not found: $gradlewName")
        }

        val args = mutableListOf<String>()
        args += gradlew.absolutePath
        if (cleanBefore) args += "cleanTest"
        args += tasksList
        // Prefer detailed output for debugging, but keep bounded by tail
        args += listOf("--continue", "--stacktrace")

        // Start a single tooling message and spinner
        val taskLine = "tasks=${args.joinToString(" ")}"
        val tool = try { project.service<ToolWindowService>().startToolingMessage("Run tests", taskLine) } catch (_: Throwable) { null }
        val spinner = try { project.service<ToolWindowService>().startSpinner("In progress...") } catch (_: Throwable) { null }

        val process = try {
            ProcessBuilder(args)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            spinner?.stopError("Failed to start gradle")
            tool?.setText(taskLine + "\n\nFailed to start gradle: ${e.message}")
            return RunTestsResult(false,0,0,0, emptyList(), null, "Failed to start gradle: ${e.message}")
        }

        val output = StringBuilder()
        val start = System.currentTimeMillis()
        var lastProgressAt = 0L
        var currentTask: String? = null
        var passed = 0
        var failed = 0
        var skipped = 0
        val recent = LinkedList<String>()
        val recentCapacity = 50

        // Heuristics:
        val progressRegex = Regex("^.+?>\\s*(.+?)\\s+(PASSED|FAILED|SKIPPED)\\s*$")
        val taskRegex = Regex("^>\\s*Task\\s*:(.+)$")
        val summaryRegex = Regex("(\\d+)\\s+tests?\\s+completed,\\s+(\\d+)\\s+failed,\\s+(\\d+)\\s+skipped", RegexOption.IGNORE_CASE)

        fun rebuildPanelText(): String {
            val elapsedSec = ((System.currentTimeMillis() - start) / 1000)
            val taskInfo = currentTask?.let { "\nCurrent: > Task :$it" } ?: ""
            val body = buildString {
                if (recent.isNotEmpty()) {
                    append(recent.joinToString("\n"))
                    append("\n")
                }
                append("Elapsed: ")
                append(elapsedSec)
                append("s, passed: ")
                append(passed)
                append(", failed: ")
                append(failed)
                append(", skipped: ")
                append(skipped)
                append(taskInfo)
            }
            return "$taskLine\n\n$body"
        }

        fun pushRecent(line: String) {
            recent.add(line)
            if (recent.size > recentCapacity) recent.removeFirst()
        }

        fun maybePulseProgress() {
            val now = System.currentTimeMillis()
            if (now - lastProgressAt >= 2000) {
                lastProgressAt = now
                tool?.setText(rebuildPanelText())
            }
        }

        process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val ln = line!!
                output.appendLine(ln)

                taskRegex.find(ln)?.let { m ->
                    currentTask = m.groupValues[1].trim()
                }

                progressRegex.find(ln)?.let { m ->
                    val status = m.groupValues[2].uppercase()
                    when (status) {
                        "PASSED" -> passed++
                        "FAILED" -> failed++
                        "SKIPPED" -> skipped++
                    }
                    val testName = m.groupValues[1]
                    pushRecent("$testName: ${status.lowercase()}")
                    tool?.setText(rebuildPanelText())
                }

                summaryRegex.find(ln)?.let { m ->
                    val cTotal = m.groupValues[1].toIntOrNull()
                    val cFailed = m.groupValues[2].toIntOrNull()
                    val cSkipped = m.groupValues[3].toIntOrNull()
                    if (cTotal != null && cFailed != null && cSkipped != null) {
                        passed = (cTotal - cFailed - cSkipped).coerceAtLeast(0)
                    }
                }

                maybePulseProgress()
            }
        }

        val finished = process.waitFor(30, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            spinner?.stopError("Timeout")
            tool?.setText(taskLine + "\n\nTimeout while running Gradle tests")
            return RunTestsResult(false,0,0,0, emptyList(), null, "Gradle test execution timed out")
        }
        val exitCode = process.exitValue()

        val tail = if (stdoutTailLines > 0) {
            val lines = output.toString().lines()
            val count = if (stdoutTailLines > lines.size) lines.size else stdoutTailLines
            lines.takeLast(count).joinToString("\n")
        } else null

        // Refresh VFS for reports dir asynchronously, then parse synchronously from IO to avoid read lock issues
        val reportsPath = reportsDir?.takeIf { it.isNotBlank() } ?: "build/test-results/test"
        VfsUtil.markDirtyAndRefresh(true, true, true, File(basePath, reportsPath))

        val parseResult = parseReports(File(basePath, reportsPath))
        val success = exitCode == 0

        if (success) {
            spinner?.stopSuccess()
            val finalText = if (parseResult.failed == 0) {
                rebuildPanelText() + "\nAll tests passed"
            } else {
                rebuildPanelText() + "\n${parseResult.failed} tests failed out of ${parseResult.total}"
            }
            tool?.setText(finalText)
        } else {
            spinner?.stopError("Gradle exit=$exitCode")
            tool?.setText(rebuildPanelText() + "\nGradle failed with exit code $exitCode")
        }

        return RunTestsResult(success, parseResult.total, parseResult.failed, parseResult.skipped, parseResult.failedTests, tail, if (success) "" else "Gradle failed with exit code $exitCode")
    }

    private data class Aggregate(
        val total: Int,
        val failed: Int,
        val skipped: Int,
        val failedTests: List<TestCaseResult>,
    )

    private fun parseReports(dir: File): Aggregate {
        var total = 0
        var failed = 0
        var skipped = 0
        val failedTests = mutableListOf<TestCaseResult>()

        if (!dir.exists()) return Aggregate(0,0,0, emptyList())

        val factory = DocumentBuilderFactory.newInstance()
        dir.walkTopDown().filter { it.isFile && it.extension.equals("xml", true) }.forEach { file ->
            try {
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(file)
                doc.documentElement.normalize()
                val testcases = doc.getElementsByTagName("testcase")
                for (i in 0 until testcases.length) {
                    val node = testcases.item(i)
                    val elem = node as org.w3c.dom.Element
                    val className = elem.getAttribute("classname")
                    val name = elem.getAttribute("name")
                    val timeAttr = elem.getAttribute("time")
                    val durationMillis = timeAttr.toDoubleOrNull()?.let { (it * 1000).toLong() }

                    var status = "passed"
                    var failureMessage: String? = null
                    var failureType: String? = null
                    var failureStack: String? = null
                    var systemOut: String? = null
                    var systemErr: String? = null

                    val failures = elem.getElementsByTagName("failure")
                    val errors = elem.getElementsByTagName("error")
                    val skippedNodes = elem.getElementsByTagName("skipped")
                    val sysOutNodes = elem.getElementsByTagName("system-out")
                    val sysErrNodes = elem.getElementsByTagName("system-err")

                    if (skippedNodes.length > 0) {
                        status = "skipped"
                        skipped += 1
                    }
                    if (failures.length > 0 || errors.length > 0) {
                        status = if (errors.length > 0) "error" else "failed"
                        val nodeList = if (errors.length > 0) errors else failures
                        val fe = nodeList.item(0) as org.w3c.dom.Element
                        failureMessage = fe.getAttribute("message").takeIf { it.isNotEmpty() }
                        failureType = fe.getAttribute("type").takeIf { it.isNotEmpty() }
                        failureStack = fe.textContent
                        failed += 1
                    }
                    if (sysOutNodes.length > 0) systemOut = (sysOutNodes.item(0) as org.w3c.dom.Element).textContent
                    if (sysErrNodes.length > 0) systemErr = (sysErrNodes.item(0) as org.w3c.dom.Element).textContent

                    total += 1

                    if (status == "failed" || status == "error") {
                        failedTests += TestCaseResult(
                            className = className,
                            name = name,
                            status = status,
                            durationMillis = durationMillis,
                            failureMessage = failureMessage,
                            failureType = failureType,
                            failureStackTrace = failureStack,
                            systemOut = systemOut,
                            systemErr = systemErr,
                            reportFilePath = file.absolutePath
                        )
                    }
                }
            } catch (_: Exception) {
                // ignore malformed files
            }
        }
        return Aggregate(total, failed, skipped, failedTests)
    }
}
