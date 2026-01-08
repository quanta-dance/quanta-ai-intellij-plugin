// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.builder

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.github.quanta_dance.quanta.plugins.intellij.tools.models.GetTestInfoResult
import com.github.quanta_dance.quanta.plugins.intellij.tools.models.TestCaseResult
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@JsonClassDescription("Get detailed information for a specific test case from Gradle XML reports")
class GetTestInfoTool : ToolInterface<GetTestInfoResult> {
    @field:JsonPropertyDescription("Fully qualified test class name to look up")
    var testClass: String? = null

    @field:JsonPropertyDescription("Test method name")
    var testName: String? = null

    @field:JsonPropertyDescription("Path to XML reports directory relative to project root. Default: 'build/test-results/test'")
    var reportsDir: String? = null

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

    override fun execute(project: Project): GetTestInfoResult {
        val basePath = project.basePath ?: return GetTestInfoResult(error = "Project base path not found")
        val klass = testClass?.trim().orEmpty()
        val name = testName?.trim().orEmpty()
        if (klass.isEmpty() || name.isEmpty()) {
            return GetTestInfoResult(error = "testClass and testName must be provided")
        }

        val reportsPath = reportsDir?.takeIf { it.isNotBlank() } ?: "build/test-results/test"
        val dir = File(basePath, reportsPath)
        if (!dir.exists()) return GetTestInfoResult(error = "Reports directory not found: ${dir.absolutePath}")

        val factory = DocumentBuilderFactory.newInstance()
        dir.walkTopDown().filter { it.isFile && it.extension.equals("xml", true) }.forEach { file ->
            try {
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(file)
                doc.documentElement.normalize()
                val testcases = doc.getElementsByTagName("testcase")
                for (i in 0 until testcases.length) {
                    val node = testcases.item(i)
                    val elem = node as Element
                    val className = elem.getAttribute("classname")
                    val methodName = elem.getAttribute("name")
                    if (className == klass && methodName == name) {
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

                        if (skippedNodes.length > 0) status = "skipped"
                        if (failures.length > 0 || errors.length > 0) {
                            status = if (errors.length > 0) "error" else "failed"
                            val nodeList = if (errors.length > 0) errors else failures
                            val fe = nodeList.item(0) as Element
                            failureMessage = fe.getAttribute("message").takeIf { it.isNotEmpty() }
                            failureType = fe.getAttribute("type").takeIf { it.isNotEmpty() }
                            failureStack = fe.textContent
                        }
                        if (sysOutNodes.length > 0) systemOut = (sysOutNodes.item(0) as Element).textContent
                        if (sysErrNodes.length > 0) systemErr = (sysErrNodes.item(0) as Element).textContent

                        val test =
                            TestCaseResult(
                                className = className,
                                name = methodName,
                                status = status,
                                durationMillis = durationMillis,
                                failureMessage = failureMessage,
                                failureType = failureType,
                                failureStackTrace = failureStack,
                                systemOut = systemOut,
                                systemErr = systemErr,
                                reportFilePath = file.absolutePath,
                            )
                        addMsg(project, "Get test info", "$klass#$name -> $status from ${file.name}")
                        return GetTestInfoResult(test = test)
                    }
                }
            } catch (_: Exception) {
                // ignore malformed files
            }
        }
        return GetTestInfoResult(error = "Test case not found in reports: $klass#$name")
    }
}
