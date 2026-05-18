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
    fun `approved blocking reason type allows hard blocked active plan response`() {
        val message =
            OpenAIResponse(
                summaryMessage = "Blocked on missing credential",
                ttsSummary = "Blocked",
                nextStep = "WAIT_USER",
                planNeedsUserConfirmation = true,
                planBlockingQuestion = "What API token should I use for the configured staging environment?",
                blockingReasonType = "MISSING_CREDENTIAL",
            )

        assertTrue(policy.isApprovedBlockingReasonType(message.blockingReasonType))
        assertTrue(policy.isHardBlockedActivePlanResponse(message))
    }

    @Test
    fun `fallback heuristic still treats missing external dependency as hard block`() {
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
