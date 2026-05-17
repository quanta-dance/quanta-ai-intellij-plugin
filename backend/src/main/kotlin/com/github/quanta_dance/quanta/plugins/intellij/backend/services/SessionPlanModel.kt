// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import kotlinx.serialization.Serializable

@Serializable
data class SessionPlanTask(
    val text: String,
    val completed: Boolean = false,
)

@Serializable
data class SessionPlan(
    val status: String = "DRAFT",
    val goal: String = "",
    val definitionOfDone: String = "",
    val tasks: List<SessionPlanTask> = emptyList(),
    val revision: Long = 0,
) {
    fun normalizedStatus(): String = status.trim().ifBlank { "DRAFT" }.uppercase()

    fun isActive(): Boolean = normalizedStatus() == "ACTIVE"

    fun hasUncheckedTasks(): Boolean = tasks.any { !it.completed }

    fun uncheckedTaskTexts(): List<String> = tasks.filter { !it.completed }.map { it.text }

    fun completedTaskTexts(): List<String> = tasks.filter { it.completed }.map { it.text }

    fun hasMeaningfulContent(): Boolean =
        goal.isNotBlank() || definitionOfDone.isNotBlank() || tasks.isNotEmpty()
}

object SessionPlanMarkdownCodec {
    fun parse(text: String): SessionPlan {
        var status = ""
        var goal = ""
        var definitionOfDone = ""
        val tasks = mutableListOf<SessionPlanTask>()
        val lines = text.lines()
        var section: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Status:", ignoreCase = true)) {
                status = trimmed.substringAfter(':').trim()
                continue
            }
            if (trimmed.equals("Goal:", ignoreCase = true) || trimmed.startsWith("Goal:", ignoreCase = true)) {
                section = "goal"
                goal = trimmed.removePrefix("Goal:").trim()
                continue
            }
            if (
                trimmed.equals("Definition of done:", ignoreCase = true) ||
                trimmed.startsWith("Definition of done:", ignoreCase = true)
            ) {
                section = "dod"
                definitionOfDone = trimmed.removePrefix("Definition of done:").trim()
                continue
            }
            if (trimmed.equals("Tasks:", ignoreCase = true)) {
                section = "tasks"
                continue
            }
            when (section) {
                "goal" -> {
                    if (trimmed.isNotBlank()) {
                        goal = listOf(goal, trimmed).filter { it.isNotBlank() }.joinToString("\n")
                    }
                }

                "dod" -> {
                    if (trimmed.isNotBlank()) {
                        definitionOfDone =
                            listOf(definitionOfDone, trimmed).filter { it.isNotBlank() }.joinToString("\n")
                    }
                }

                "tasks" -> {
                    val match = Regex("^- \\[( |x|X)]\\s+(.*)$").find(trimmed) ?: continue
                    tasks += SessionPlanTask(
                        text = match.groupValues[2].trim(),
                        completed = match.groupValues[1].equals("x", ignoreCase = true),
                    )
                }
            }
        }

        return SessionPlan(
            status = status.ifBlank { "DRAFT" },
            goal = goal,
            definitionOfDone = definitionOfDone,
            tasks = tasks,
        )
    }

    fun render(plan: SessionPlan): String {
        if (!plan.hasMeaningfulContent()) return ""
        return buildString {
            appendLine("# Session Plan")
            appendLine("Status: ${plan.normalizedStatus()}")
            appendLine()
            appendLine("Goal:")
            appendLine(plan.goal.trim())
            appendLine()
            appendLine("Definition of done:")
            appendLine(plan.definitionOfDone.trim())
            appendLine()
            appendLine("Tasks:")
            plan.tasks.forEach { task ->
                val mark = if (task.completed) "x" else " "
                append("- [").append(mark).append("] ").appendLine(task.text.trim())
            }
        }
    }
}
