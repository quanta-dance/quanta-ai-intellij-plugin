// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models.OpenAIResponse
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentTurnContinuationPolicyTest {
    private val policy = AgentTurnContinuationPolicy()

    @Test
    fun `plan mutation fields require explicit tool persistence`() {
        val message =
            OpenAIResponse(
                summaryMessage = "Proposed plan update",
                ttsSummary = "Plan update",
                planStatus = "ACTIVE",
                planGoal = "Refactor plan execution",
                planDefinitionOfDone = "Plan transitions are explicit",
                planTasks = listOf("Update orchestrator"),
                planCompletedTasks = listOf("Inspect current behavior"),
            )

        assertTrue(policy.responseAttemptsPlanMutationWithoutTool(message))
    }

    @Test
    fun `non plan response does not count as plan mutation`() {
        val message =
            OpenAIResponse(
                summaryMessage = "I inspected the code and will continue.",
                ttsSummary = "Continuing",
                nextStep = "CONTINUE",
            )

        assertFalse(policy.responseAttemptsPlanMutationWithoutTool(message))
    }

    @Test
    fun `routine confirmation is not treated as a hard block`() {
        val message =
            OpenAIResponse(
                summaryMessage = "Need confirmation",
                ttsSummary = "Need confirmation",
                nextStep = "WAIT_USER",
                planNeedsUserConfirmation = true,
                planBlockingQuestion = "Should I continue with the next planned refactor?",
            )

        assertFalse(policy.isHardBlockedActivePlanResponse(message))
    }

    @Test
    fun `missing external dependency is treated as a hard block`() {
        val message =
            OpenAIResponse(
                summaryMessage = "Blocked on missing credential",
                ttsSummary = "Blocked",
                nextStep = "WAIT_USER",
                planNeedsUserConfirmation = true,
                planBlockingQuestion = "What API token should I use for the configured staging environment?",
            )

        assertTrue(policy.isHardBlockedActivePlanResponse(message))
    }
}
