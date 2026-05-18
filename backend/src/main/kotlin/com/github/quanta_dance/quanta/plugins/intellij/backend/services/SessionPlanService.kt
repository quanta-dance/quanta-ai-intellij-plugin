// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationStateService
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
class SessionPlanService(
    private val project: Project,
) {
    data class ActivationCheck(
        val valid: Boolean,
        val issues: List<String> = emptyList(),
        val currentRevision: Long = 0,
    )

    data class PlanWriteResult(
        val changed: Boolean,
        val plan: SessionPlan,
        val issue: String? = null,
    )

    private val log = Logger.getInstance(SessionPlanService::class.java)
    private val _statusFlow = MutableStateFlow(ChatPlanStatusDto())
    private val keyResolver =
        SessionPlanKeyResolver(
            mainConversationKeyProvider = { MainConversationKeyResolver(project).conversationKeyForMain() },
            activeSessionIdProvider = {
                runCatching { project.service<ChatConversationStateService>().getActiveSessionId() }.getOrNull()
            },
        )

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
        expectedRevision: Long? = null,
    ): PlanWriteResult =
        savePlan(
            SessionPlan(
                status = "DRAFT",
                goal = goal,
                definitionOfDone = definitionOfDone,
                tasks = tasks.map { SessionPlanTask(text = it.trim()) }.filter { it.text.isNotBlank() },
            ),
            expectedRevision = expectedRevision,
        )

    fun validateForActivation(): ActivationCheck {
        val plan = loadPlanSnapshot()
        val issues = mutableListOf<String>()
        if (plan.goal.isBlank()) issues += "Goal is missing."
        if (plan.definitionOfDone.isBlank()) issues += "Definition of done is missing."
        if (plan.tasks.isEmpty()) issues += "At least one task is required."
        return ActivationCheck(valid = issues.isEmpty(), issues = issues, currentRevision = plan.revision)
    }

    fun activate(expectedRevision: Long? = null): ActivationCheck {
        val plan = loadPlanSnapshot()
        if (!plan.hasMeaningfulContent()) {
            return ActivationCheck(valid = false, issues = listOf("Plan is empty."), currentRevision = plan.revision)
        }
        val validation = validateForActivation()
        if (!validation.valid) return validation
        val result = savePlan(plan.copy(status = "ACTIVE"), expectedRevision = expectedRevision)
        return if (result.issue == null) {
            ActivationCheck(valid = true, currentRevision = result.plan.revision)
        } else {
            ActivationCheck(valid = false, issues = listOf(result.issue), currentRevision = result.plan.revision)
        }
    }

    fun markTasksDone(
        completed: List<String>,
        expectedRevision: Long? = null,
    ): PlanWriteResult {
        val completedSet = completed.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (completedSet.isEmpty()) return PlanWriteResult(changed = false, plan = loadPlanSnapshot())

        val current = loadPlanSnapshot()
        if (current.tasks.isEmpty()) return PlanWriteResult(changed = false, plan = current)

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
        if (!changed) return PlanWriteResult(changed = false, plan = current)

        val updatedPlan =
            current.copy(
                status = if (updatedTasks.isNotEmpty() && updatedTasks.all { it.completed }) "DONE" else current.normalizedStatus(),
                tasks = updatedTasks,
            )
        return savePlan(updatedPlan, expectedRevision = expectedRevision)
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
            revision = plan.revision,
        )
    }

    private fun savePlan(
        plan: SessionPlan,
        expectedRevision: Long? = null,
    ): PlanWriteResult {
        val normalizedPlan =
            plan.copy(
                status = plan.normalizedStatus(),
                goal = plan.goal.trim(),
                definitionOfDone = plan.definitionOfDone.trim(),
                tasks = plan.tasks.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotBlank() },
            )
        try {
            val state = QuantaAISessionState.instance.state
            val key = conversationKey()
            val current = state.sessionPlans[key] ?: SessionPlan()
            if (expectedRevision != null && expectedRevision != current.revision) {
                return PlanWriteResult(
                    changed = false,
                    plan = current,
                    issue = "Plan revision mismatch. Expected revision $expectedRevision but current revision is ${current.revision}.",
                )
            }
            val nextPlan = normalizedPlan.copy(revision = current.revision + 1)
            if (nextPlan.hasMeaningfulContent()) {
                state.sessionPlans[key] = nextPlan
            } else {
                state.sessionPlans.remove(key)
            }
            publishCurrentStatus()
            try {
                project.service<SessionMemoryService>().refreshFromCurrentState(
                    reason = "plan_update",
                    explicitNote = "Session plan updated: ${nextPlan.normalizedStatus()}",
                )
            } catch (_: Throwable) {
            }
            return PlanWriteResult(changed = true, plan = nextPlan)
        } catch (t: Throwable) {
            log.warn("Failed to persist session plan state: ${t.message}", t)
            return PlanWriteResult(changed = false, plan = loadPlanSnapshot(), issue = t.message)
        }
    }

    private fun conversationKey(): String = keyResolver.currentKey()
}
