// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models.OpenAIResponse

/**
 * Encapsulates lightweight policy decisions used by the agent-turn continuation loop.
 *
 * This keeps plan-loop signature shaping and routine-confirmation heuristics out of
 * [OpenAIService], making the remaining agent-loop extraction easier.
 */
class AgentTurnContinuationPolicy {
    companion object {
        private val APPROVED_BLOCKING_REASON_TYPES =
            setOf(
                "MISSING_EXTERNAL_INFO",
                "MISSING_CREDENTIAL",
                "USER_DECISION_REQUIRED",
                "TOOL_FAILURE_REQUIRES_USER",
            )
    }

    fun buildPlanLoopSignature(
        message: OpenAIResponse,
        effectivePlanStatus: String?,
        summaryText: String,
    ): String =
        buildString {
            append(message.nextStep?.uppercase().orEmpty())
            append('|').append(effectivePlanStatus.orEmpty())
            append('|').append(message.planNeedsUserConfirmation == true)
            append('|').append(
                message.blockingReasonType
                    ?.trim()
                    ?.uppercase()
                    .orEmpty(),
            )
            append('|').append(
                message.planCompletedTasks
                    ?.sorted()
                    ?.joinToString("||")
                    .orEmpty(),
            )
            append('|').append(normalizePlanLoopSummary(summaryText))
        }

    fun responseAttemptsPlanMutationWithoutTool(message: OpenAIResponse): Boolean =
        !message.planStatus.isNullOrBlank() ||
            !message.planGoal.isNullOrBlank() ||
            !message.planDefinitionOfDone.isNullOrBlank() ||
            !message.planTasks.isNullOrEmpty() ||
            !message.planCompletedTasks.isNullOrEmpty()

    fun isHardBlockedActivePlanResponse(message: OpenAIResponse): Boolean {
        val blockingQuestion = message.planBlockingQuestion?.trim().orEmpty()
        val approvedReasonType = normalizeBlockingReasonType(message.blockingReasonType)
        if (message.planNeedsUserConfirmation != true || message.nextStep?.uppercase() != "WAIT_USER") return false
        if (approvedReasonType != null) return blockingQuestion.isNotBlank()
        return blockingQuestion.isNotBlank() && !isRoutineConfirmationQuestion(blockingQuestion)
    }

    fun isApprovedBlockingReasonType(blockingReasonType: String?): Boolean = normalizeBlockingReasonType(blockingReasonType) != null

    fun isRoutineConfirmationQuestion(question: String): Boolean {
        val q = question.trim().lowercase()
        if (q.isBlank()) return false
        val leadingPatterns =
            listOf(
                "should i",
                "do you want",
                "would you like",
                "shall i",
                "may i",
                "can i continue",
                "can i proceed",
                "please confirm",
                "confirm that i should",
            )
        if (leadingPatterns.any { q.startsWith(it) }) return true
        val contextualPatterns =
            listOf(
                "before i continue",
                "before proceeding",
                "before i make changes",
                "before applying",
                "before running",
            )
        return contextualPatterns.any { q.contains(it) }
    }

    private fun normalizeBlockingReasonType(blockingReasonType: String?): String? {
        val normalized = blockingReasonType?.trim()?.uppercase().orEmpty()
        return normalized.takeIf { APPROVED_BLOCKING_REASON_TYPES.contains(it) }
    }

    private fun normalizePlanLoopSummary(text: String): String =
        text
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[^a-z0-9 _|:-]"), "")
            .trim()
            .take(240)
}
