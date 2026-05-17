// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.QuantaAISessionState
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatPlanStatusDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Service(Service.Level.PROJECT)
class SessionPlanService(private val project: Project) {
    data class ActivationCheck(
        val valid: Boolean,
        val issues: List<String> = emptyList(),
    )

    private val log = Logger.getInstance(SessionPlanService::class.java)
    private val _statusFlow = MutableStateFlow(ChatPlanStatusDto())
    private val conversationKeyResolver = MainConversationKeyResolver(project)

    val statusFlow: StateFlow<ChatPlanStatusDto> = _statusFlow.asStateFlow()

    fun loadPlanSnapshot(): SessionPlan =
        try {
            QuantaAISessionState.instance.state.sessionPlans[conversationKey()] ?: SessionPlan()
        } catch (t: Throwable) {
            log.warn("Failed to load session plan state: ${t.message}", t)
            SessionPlan()
        }

    fun loadText(maxChars: Int = 16_000): String {
        val text = SessionPlanMarkdownCodec.render(loadPlanSnapshot())
        if (text.isBlank()) return ""
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n... (truncated)"
    }

    fun saveDraft(
        goal: String,
        definitionOfDone: String,
        tasks: List<String>,
    ) {
        savePlan(
            SessionPlan(
                status = "DRAFT",
                goal = goal,
                definitionOfDone = definitionOfDone,
                tasks = tasks.map { SessionPlanTask(text = it.trim()) }.filter { it.text.isNotBlank() },
            ),
        )
    }

    fun validateForActivation(): ActivationCheck {
        val plan = loadPlanSnapshot()
        val issues = mutableListOf<String>()
        if (plan.goal.isBlank()) issues += "Goal is missing."
        if (plan.definitionOfDone.isBlank()) issues += "Definition of done is missing."
        if (plan.tasks.isEmpty()) issues += "At least one task is required."
        return ActivationCheck(valid = issues.isEmpty(), issues = issues)
    }

    fun activate(): ActivationCheck {
        val plan = loadPlanSnapshot()
        if (!plan.hasMeaningfulContent()) {
            return ActivationCheck(valid = false, issues = listOf("Plan is empty."))
        }
        val validation = validateForActivation()
        if (!validation.valid) return validation
        savePlan(plan.copy(status = "ACTIVE"))
        return ActivationCheck(valid = true)
    }

    fun markTasksDone(completed: List<String>): Boolean {
        val completedSet = completed.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (completedSet.isEmpty()) return false

        val current = loadPlanSnapshot()
        if (current.tasks.isEmpty()) return false

        var changed = false
        val updatedTasks =
            current.tasks.map { task ->
                if (completedSet.contains(task.text.trim()) && !task.completed) {
                    changed = true
                    task.copy(completed = true)
                } else {
                    task
                }
            }
        if (!changed) return false

        val updatedPlan =
            current.copy(
                status = if (updatedTasks.isNotEmpty() && updatedTasks.all { it.completed }) "DONE" else current.normalizedStatus(),
                tasks = updatedTasks,
            )
        savePlan(updatedPlan)
        return true
    }

    fun isActive(): Boolean = loadPlanSnapshot().isActive()

    fun hasUncheckedTasks(): Boolean = loadPlanSnapshot().hasUncheckedTasks()

    fun getStatus(): String = loadPlanSnapshot().normalizedStatus()

    fun getCurrentPlanStatus(): ChatPlanStatusDto = planStatusDto(loadPlanSnapshot())

    fun publishCurrentStatus() {
        _statusFlow.value = getCurrentPlanStatus()
    }

    private fun planStatusDto(plan: SessionPlan): ChatPlanStatusDto {
        if (!plan.hasMeaningfulContent()) return ChatPlanStatusDto()
        return ChatPlanStatusDto(
            status = plan.normalizedStatus(),
            text = SessionPlanMarkdownCodec.render(plan),
            goal = plan.goal,
            definitionOfDone = plan.definitionOfDone,
            tasks = plan.tasks.map { it.text },
            completedTasks = plan.completedTaskTexts(),
            uncheckedTasks = plan.uncheckedTaskTexts(),
        )
    }

    private fun savePlan(plan: SessionPlan) {
        val normalizedPlan =
            plan.copy(
                status = plan.normalizedStatus(),
                goal = plan.goal.trim(),
                definitionOfDone = plan.definitionOfDone.trim(),
                tasks = plan.tasks.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotBlank() },
            )
        try {
            val state = QuantaAISessionState.instance.state
            if (normalizedPlan.hasMeaningfulContent()) {
                state.sessionPlans[conversationKey()] = normalizedPlan
            } else {
                state.sessionPlans.remove(conversationKey())
            }
            publishCurrentStatus()
            try {
                project.service<SessionMemoryService>().refreshFromCurrentState(
                    reason = "plan_update",
                    explicitNote = "Session plan updated: ${normalizedPlan.normalizedStatus()}",
                )
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            log.warn("Failed to persist session plan state: ${t.message}", t)
        }
    }

    private fun conversationKey(): String = conversationKeyResolver.conversationKeyForMain()
}
