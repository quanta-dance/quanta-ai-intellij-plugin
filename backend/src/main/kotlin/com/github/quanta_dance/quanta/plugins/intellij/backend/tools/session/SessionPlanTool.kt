// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.session

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.SessionPlanService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Backend tool for maintaining the session-level execution plan shared across turns.
 *
 * It is the main persistence layer for cooperative planning, allowing agents to draft, activate,
 * read, and complete work items through typed session state.
 */
@JsonClassDescription(
    "Manage the cooperative session plan state for the current chat session. " +
        "Use this tool only for substantial multi-step work that benefits from tracked execution. " +
        "Do not create a plan for simple questions, tiny edits, or one-off lookups. " +
        "Use this tool to draft a plan, activate it after user approval, mark tasks completed, or read the current plan.",
)
class SessionPlanTool : ToolInterface<String> {
    @field:JsonPropertyDescription("Action to perform: READ | DRAFT | ACTIVATE | COMPLETE | SET_DONE")
    var action: String? = null

    @field:JsonPropertyDescription("Plan goal (used for DRAFT)")
    var goal: String? = null

    @field:JsonPropertyDescription("Definition of done (used for DRAFT)")
    var definitionOfDone: String? = null

    @field:JsonPropertyDescription("Tasks (used for DRAFT). Provide plain text items without [ ] markers.")
    var tasks: List<String>? = null

    @field:JsonPropertyDescription("Completed tasks to mark as [x] (used for COMPLETE). Must match existing task text.")
    var completedTasks: List<String>? = null

    @field:JsonPropertyDescription("Optional expected current plan revision for stale-write protection.")
    var expectedRevision: Long? = null

    @field:JsonPropertyDescription("Maximum characters to return (default 8000)")
    var maxChars: Int? = null

    override fun execute(project: Project): String {
        val svc = project.service<SessionPlanService>()
        val act =
            action
                ?.trim()
                ?.uppercase()
                .orEmpty()
                .ifBlank { "READ" }
        val limit = (maxChars ?: 8_000).coerceIn(200, 64_000)

        return when (act) {
            "READ" -> {
                svc.loadText(maxChars = limit)
            }

            "DRAFT" -> {
                val result =
                    svc.saveDraft(
                        goal = goal?.trim().orEmpty(),
                        definitionOfDone = definitionOfDone?.trim().orEmpty(),
                        tasks = (tasks ?: emptyList()).map { it.trim() }.filter { it.isNotBlank() },
                        expectedRevision = expectedRevision,
                    )
                renderResult(result.issue, svc.loadText(maxChars = limit), result.plan.revision)
            }

            "ACTIVATE" -> {
                val result = svc.activate(expectedRevision = expectedRevision)
                if (result.valid) {
                    renderResult(null, svc.loadText(maxChars = limit), result.currentRevision)
                } else {
                    buildString {
                        appendLine("Plan remains DRAFT because it is not ready to activate.")
                        result.issues.forEach { appendLine("- $it") }
                        appendLine()
                        append(renderResult(null, svc.loadText(maxChars = limit), result.currentRevision))
                    }
                }
            }

            "COMPLETE" -> {
                val completed = (completedTasks ?: emptyList()).map { it.trim() }.filter { it.isNotBlank() }
                val result = svc.markTasksDone(completed, expectedRevision = expectedRevision)
                renderResult(result.issue, svc.loadText(maxChars = limit), result.plan.revision)
            }

            "SET_DONE" -> {
                val plan = svc.loadPlanSnapshot()
                val result = svc.markTasksDone(plan.uncheckedTaskTexts(), expectedRevision = expectedRevision)
                renderResult(result.issue, svc.loadText(maxChars = limit), result.plan.revision)
            }

            else -> {
                "Unknown action '$act'. Supported actions: READ, DRAFT, ACTIVATE, COMPLETE, SET_DONE"
            }
        }
    }

    private fun renderResult(
        issue: String?,
        planText: String,
        revision: Long,
    ): String =
        buildString {
            issue?.takeIf { it.isNotBlank() }?.let {
                appendLine(it)
                appendLine()
            }
            appendLine("Revision: $revision")
            if (planText.isNotBlank()) {
                appendLine()
                append(planText)
            }
        }.trim()
}
