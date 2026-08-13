// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models.OpenAIResponse
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActiveSessionPlanCoordinatorTest {
    private val coordinator = ActiveSessionPlanCoordinator(AgentTurnContinuationPolicy())

    @Test
    fun `routine confirmation is rejected during active plan`() {
        val evaluation =
            coordinator.evaluateAssistantMessage(
                message =
                    OpenAIResponse(
                        summaryMessage = "Should I continue?",
                        ttsSummary = "Continue?",
                        nextStep = "WAIT_USER",
                        planNeedsUserConfirmation = true,
                        planBlockingQuestion = "Should I continue with the next planned step?",
                    ),
                summaryText = "Should I continue?",
                planAtEvaluation = SessionPlan(status = "ACTIVE", tasks = listOf(SessionPlanTask("Continue work"))),
                planWasActiveAtTurnStart = true,
                pendingToolOutputsEmpty = true,
                maxContinuations = 5,
                maxPlanToolEnforcementAttempts = 5,
                loopState = ActivePlanLoopState(),
            )

        assertNotNull(evaluation.retryInstruction)
        assertTrue(evaluation.retryInstruction!!.contains("Continue executing the ACTIVE plan autonomously"))
    }

    @Test
    fun `true blocked question is allowed`() {
        val evaluation =
            coordinator.evaluateAssistantMessage(
                message =
                    OpenAIResponse(
                        summaryMessage = "Blocked on missing credential.",
                        ttsSummary = "Blocked",
                        nextStep = "WAIT_USER",
                        planNeedsUserConfirmation = true,
                        planBlockingQuestion = "What API token should I use for the staging environment?",
                        blockingReasonType = "MISSING_CREDENTIAL",
                    ),
                summaryText = "Blocked on missing credential.",
                planAtEvaluation = SessionPlan(status = "ACTIVE", tasks = listOf(SessionPlanTask("Use staging API"))),
                planWasActiveAtTurnStart = true,
                pendingToolOutputsEmpty = true,
                maxContinuations = 5,
                maxPlanToolEnforcementAttempts = 5,
                loopState = ActivePlanLoopState(),
            )

        assertFalse(evaluation.retryInstruction != null)
    }

    @Test
    fun `next step done is rejected while active plan still has work`() {
        val evaluation =
            coordinator.evaluateAssistantMessage(
                message =
                    OpenAIResponse(
                        summaryMessage = "I am done.",
                        ttsSummary = "Done",
                        nextStep = "DONE",
                    ),
                summaryText = "I am done.",
                planAtEvaluation = SessionPlan(status = "ACTIVE", tasks = listOf(SessionPlanTask("Ship change"))),
                planWasActiveAtTurnStart = true,
                pendingToolOutputsEmpty = true,
                maxContinuations = 5,
                maxPlanToolEnforcementAttempts = 5,
                loopState = ActivePlanLoopState(),
            )

        assertNotNull(evaluation.retryInstruction)
        assertTrue(evaluation.retryInstruction!!.contains("Do not finish the turn with nextStep=DONE"))
    }
}
