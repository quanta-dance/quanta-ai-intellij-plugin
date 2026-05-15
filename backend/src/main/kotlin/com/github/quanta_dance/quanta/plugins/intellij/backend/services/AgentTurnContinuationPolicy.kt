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
    fun buildPlanLoopSignature(
        message: OpenAIResponse,
        effectivePlanStatus: String?,
        summaryText: String,
    ): String =
        buildString {
            append(message.nextStep?.uppercase().orEmpty())
            append('|').append(effectivePlanStatus.orEmpty())
            append('|').append(message.planNeedsUserConfirmation == true)
            append('|').append(message.planCompletedTasks?.sorted()?.joinToString("||").orEmpty())
            append('|').append(normalizePlanLoopSummary(summaryText))
        }

    fun isRoutineConfirmationQuestion(question: String): Boolean {
        val q = question.trim().lowercase()
        if (q.isBlank()) return false
        val patterns = listOf(
            "should i",
            "do you want",
            "would you like",
            "shall i",
            "may i",
            "can i continue",
            "can i proceed",
            "please confirm",
            "confirm that i should",
            "before i continue",
            "before proceeding",
            "before i make changes",
            "before applying",
            "before running",
        )
        return patterns.any { q.contains(it) }
    }

    private fun normalizePlanLoopSummary(text: String): String =
        text.lowercase()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[^a-z0-9 _|:-]"), "")
            .trim()
            .take(240)
}
