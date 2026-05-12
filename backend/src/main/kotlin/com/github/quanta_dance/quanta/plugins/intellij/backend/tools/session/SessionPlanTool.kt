// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.session

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.SessionPlanService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.project.Project

@JsonClassDescription(
    "Manage the cooperative session plan stored at .quantadance/session/plan.md. " +
            "Use this tool to draft a plan, activate it after user approval, mark tasks completed, or read the current plan.",
)
/**
 * Backend tool for maintaining the session-level execution plan shared across turns.
 *
 * It is the main persistence layer for cooperative planning, allowing agents to draft, activate,
 * read, and complete work items in a controlled markdown format.
 */
class SessionPlanTool : ToolInterface<String> {
    @field:JsonPropertyDescription("Action to perform: READ | DRAFT | ACTIVATE | COMPLETE")
    var action: String? = null

    @field:JsonPropertyDescription("Plan goal (used for DRAFT)")
    var goal: String? = null

    @field:JsonPropertyDescription("Definition of done (used for DRAFT)")
    var definitionOfDone: String? = null

    @field:JsonPropertyDescription("Tasks (used for DRAFT). Provide plain text items without [ ] markers.")
    var tasks: List<String>? = null

    @field:JsonPropertyDescription("Completed tasks to mark as [x] (used for COMPLETE). Must match existing task text.")
    var completedTasks: List<String>? = null

    @field:JsonPropertyDescription("Maximum characters to return (default 8000)")
    var maxChars: Int? = null

    override fun execute(project: Project): String {
        val svc = SessionPlanService(project)
        val act = action?.trim()?.uppercase().orEmpty().ifBlank { "READ" }
        val limit = (maxChars ?: 8_000).coerceIn(200, 64_000)

        return when (act) {
            "READ" -> svc.loadText(maxChars = limit)
            "DRAFT" -> {
                svc.saveDraft(
                    goal = goal?.trim().orEmpty(),
                    definitionOfDone = definitionOfDone?.trim().orEmpty(),
                    tasks = (tasks ?: emptyList()).map { it.trim() }.filter { it.isNotBlank() },
                )
                svc.loadText(maxChars = limit)
            }

            "ACTIVATE" -> {
                val result = svc.activate()
                if (result.valid) {
                    svc.loadText(maxChars = limit)
                } else {
                    buildString {
                        appendLine("Plan remains DRAFT because it is not specific enough to execute safely.")
                        result.issues.forEach { appendLine("- $it") }
                        appendLine()
                        append(svc.loadText(maxChars = limit))
                    }
                }
            }

            "COMPLETE" -> {
                val completed = (completedTasks ?: emptyList()).map { it.trim() }.filter { it.isNotBlank() }
                svc.markTasksDone(completed)
                if (completed.isNotEmpty() && svc.onlyPassiveTailTasksRemain()) {
                    svc.markAllTasksDone()
                }
                svc.loadText(maxChars = limit)
            }

            else -> "Unknown action: '$act'. Supported: READ, DRAFT, ACTIVATE, COMPLETE."
        }
    }
}
