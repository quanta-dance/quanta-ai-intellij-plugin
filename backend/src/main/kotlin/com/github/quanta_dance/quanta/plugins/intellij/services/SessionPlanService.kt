// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatPlanStatusDto
import com.github.quanta_dance.quanta.plugins.intellij.tools.PathUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

class SessionPlanService(private val project: Project) {
    private val log = Logger.getInstance(SessionPlanService::class.java)

    companion object {
        private const val DIR = ".quantadance/session"
        private const val FILE = "plan.md"
    }

    private fun planFileIo(): File? {
        val base = PathUtils.projectRootPath(project)
        if (base.isNullOrBlank()) {
            QDLog.warn(log) { "SessionPlanService: project root path is null/blank; cannot resolve plan path" }
            return null
        }
        return File(File(base, DIR), FILE)
    }

    fun loadText(maxChars: Int = 16_000): String {
        val io = planFileIo() ?: return ""
        return try {
            if (!io.exists()) return ""
            val text = io.readText()
            if (text.length <= maxChars) text else text.take(maxChars) + "\n... (truncated)"
        } catch (t: Throwable) {
            Logger.getInstance(SessionPlanService::class.java).warn("Failed to read plan.md: ${t.message}", t)
            ""
        }
    }

    fun ensureExistsDraft(
        goal: String = "",
        definitionOfDone: String = "",
        tasks: List<String> = emptyList(),
    ) {
        val io = planFileIo() ?: return
        if (io.exists()) return
        saveDraft(goal, definitionOfDone, tasks)
    }

    fun saveDraft(
        goal: String,
        definitionOfDone: String,
        tasks: List<String>,
    ) {
        savePlan(
            status = "DRAFT",
            goal = goal,
            definitionOfDone = definitionOfDone,
            tasks = tasks,
        )
    }

    fun activate() {
        val parsed = parse(loadText(maxChars = 64_000))
        if (parsed.tasks.isEmpty() && parsed.goal.isBlank() && parsed.definitionOfDone.isBlank()) return
        savePlan(
            status = "ACTIVE",
            goal = parsed.goal,
            definitionOfDone = parsed.definitionOfDone,
            tasks = parsed.tasks,
            checked = parsed.checked,
        )
    }

    fun markTasksDone(completed: List<String>): Boolean {
        if (completed.isEmpty()) return false
        val current = loadText(maxChars = 64_000)
        val parsed = parse(current)
        if (parsed.tasks.isEmpty()) return false

        val completedSet = completed.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (completedSet.isEmpty()) return false

        val newChecked = parsed.checked.toMutableSet()
        parsed.tasks.forEachIndexed { idx, task ->
            if (completedSet.contains(task.trim())) newChecked.add(idx)
        }

        val allDone = parsed.tasks.isNotEmpty() && newChecked.size >= parsed.tasks.size
        val newStatus = if (allDone) "DONE" else parsed.status.ifBlank { "ACTIVE" }

        savePlan(
            status = newStatus,
            goal = parsed.goal,
            definitionOfDone = parsed.definitionOfDone,
            tasks = parsed.tasks,
            checked = newChecked,
        )
        return true
    }

    fun isActive(): Boolean {
        val parsed = parse(loadText(maxChars = 32_000))
        return parsed.status.equals("ACTIVE", ignoreCase = true)
    }

    fun hasUncheckedTasks(): Boolean {
        val parsed = parse(loadText(maxChars = 32_000))
        return parsed.tasks.indices.any { !parsed.checked.contains(it) }
    }

    fun applyDraftFromResponse(
        goal: String?,
        definitionOfDone: String?,
        tasks: List<String>?,
    ): Boolean {
        val normalizedTasks = tasks.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
        val normalizedGoal = goal?.trim().orEmpty()
        val normalizedDod = definitionOfDone?.trim().orEmpty()
        if (normalizedTasks.isEmpty() && normalizedGoal.isBlank() && normalizedDod.isBlank()) return false
        saveDraft(normalizedGoal, normalizedDod, normalizedTasks)
        return true
    }

    fun markAllTasksDone(): Boolean {
        val parsed = parse(loadText(maxChars = 64_000))
        if (parsed.tasks.isEmpty()) return false
        savePlan(
            status = "DONE",
            goal = parsed.goal,
            definitionOfDone = parsed.definitionOfDone,
            tasks = parsed.tasks,
            checked = parsed.tasks.indices.toSet(),
        )
        return true
    }

    fun hasPlan(): Boolean = loadText(maxChars = 200).isNotBlank()

    fun getStatus(): String {
        val parsed = parse(loadText(maxChars = 32_000))
        return parsed.status.trim().ifBlank { "DRAFT" }
    }

    fun getCurrentPlanStatus(): ChatPlanStatusDto {
        val text = loadText(maxChars = 8_000)
        val status =
            text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("Status:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                .orEmpty()
        return ChatPlanStatusDto(status = status, text = text)
    }

    private data class ParsedPlan(
        val status: String,
        val goal: String,
        val definitionOfDone: String,
        val tasks: List<String>,
        val checked: Set<Int>,
    )

    private fun parse(text: String): ParsedPlan {
        var status = ""
        var goal = ""
        var dod = ""
        val tasks = mutableListOf<String>()
        val checked = mutableSetOf<Int>()

        val lines = text.lines()
        var section: String? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Status:", ignoreCase = true)) {
                status = trimmed.removePrefix("Status:").trim()
                continue
            }
            if (trimmed.equals("Goal:", ignoreCase = true) || trimmed.startsWith("Goal:", ignoreCase = true)) {
                section = "goal"
                goal = trimmed.removePrefix("Goal:").trim()
                continue
            }
            if (trimmed.equals("Definition of done:", ignoreCase = true) ||
                trimmed.startsWith("Definition of done:", ignoreCase = true)
            ) {
                section = "dod"
                dod = trimmed.removePrefix("Definition of done:").trim()
                continue
            }
            if (trimmed.equals("Tasks:", ignoreCase = true) || trimmed.startsWith("Tasks:", ignoreCase = true)) {
                section = "tasks"
                continue
            }

            if (section == "goal") {
                if (goal.isBlank() && trimmed.isNotBlank()) goal = trimmed
            } else if (section == "dod") {
                if (dod.isBlank() && trimmed.isNotBlank()) dod = trimmed
            } else if (section == "tasks") {
                val m = Regex("^- \\[( |x|X)]\\s+(.*)$").find(trimmed)
                if (m != null) {
                    val isChecked = m.groupValues[1].equals("x", ignoreCase = true)
                    val taskText = m.groupValues[2].trim()
                    val idx = tasks.size
                    tasks.add(taskText)
                    if (isChecked) checked.add(idx)
                }
            }
        }

        return ParsedPlan(
            status = status.ifBlank { "DRAFT" },
            goal = goal,
            definitionOfDone = dod,
            tasks = tasks,
            checked = checked,
        )
    }

    private fun savePlan(
        status: String,
        goal: String,
        definitionOfDone: String,
        tasks: List<String>,
        checked: Set<Int> = emptySet(),
    ) {
        val io = planFileIo() ?: return
        try {
            if (!io.parentFile.exists()) io.parentFile.mkdirs()
            val content =
                buildString {
                    appendLine("# Session Plan")
                    appendLine("Status: ${status.trim().ifBlank { "DRAFT" }}")
                    appendLine()
                    appendLine("Goal:")
                    appendLine(goal.trim())
                    appendLine()
                    appendLine("Definition of done:")
                    appendLine(definitionOfDone.trim())
                    appendLine()
                    appendLine("Tasks:")
                    tasks.forEachIndexed { idx, t ->
                        val mark = if (checked.contains(idx)) "x" else " "
                        append("- [").append(mark).append("] ").appendLine(t.trim())
                    }
                }
            io.writeText(content)
            QDLog.info(log) { "Session plan written: ${io.absolutePath} (chars=${content.length})" }
            refresh(io)
            runCatching {
                project.service<SessionPlanStatusService>().publish(getCurrentPlanStatus())
            }
            try {
                project.service<SessionMemoryService>().refreshFromCurrentState(
                    reason = "plan_update",
                    explicitNote = "Session plan updated: ${status.trim().ifBlank { "DRAFT" }}",
                )
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            log.warn("Failed to write plan.md: ${t.message}", t)
        }
    }

    private fun refresh(io: File) {
        try {
            val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)
            if (vFile != null) {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        vFile.refresh(false, false)
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }
}
