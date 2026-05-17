// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models.OpenAIResponse

data class ActivePlanLoopState(
    val continuationCount: Int = 0,
    val forcePlanToolAttempts: Int = 0,
    val lastPlanLoopSignature: String? = null,
    val repeatedPlanLoopSignatureCount: Int = 0,
)

data class ActivePlanEvaluation(
    val retryInstruction: String? = null,
    val loopState: ActivePlanLoopState,
    val effectivePlanStatus: String,
    val activePlanStillHasWork: Boolean,
)

class ActiveSessionPlanCoordinator(
    private val continuationPolicy: AgentTurnContinuationPolicy,
) {
    fun evaluateAssistantMessage(
        message: OpenAIResponse,
        summaryText: String,
        sessionPlanToolCalledThisTurn: Boolean,
        planAtEvaluation: SessionPlan,
        planWasActiveAtTurnStart: Boolean,
        pendingToolOutputsEmpty: Boolean,
        maxContinuations: Int,
        maxPlanToolEnforcementAttempts: Int,
        loopState: ActivePlanLoopState,
    ): ActivePlanEvaluation {
        if (continuationPolicy.responseAttemptsPlanMutationWithoutTool(message) && !sessionPlanToolCalledThisTurn) {
            return retry(
                message =
                    "Persist all session plan changes exclusively through SessionPlanTool. Do not rely on planStatus, " +
                            "planGoal, planTasks, or planCompletedTasks response fields to change the plan. Call SessionPlanTool first, then respond.",
                loopState = loopState,
                countAsPlanToolEnforcement = true,
                maxContinuations = maxContinuations,
                maxPlanToolEnforcementAttempts = maxPlanToolEnforcementAttempts,
                effectivePlanStatus = planAtEvaluation.normalizedStatus(),
                activePlanStillHasWork = planAtEvaluation.isActive() && planAtEvaluation.hasUncheckedTasks(),
            )
        }

        val effectivePlanStatus = planAtEvaluation.normalizedStatus()
        val activePlanStillHasWork = planAtEvaluation.isActive() && planAtEvaluation.hasUncheckedTasks()

        if (planWasActiveAtTurnStart && message.nextStep?.uppercase() == "DONE" && effectivePlanStatus != "DONE") {
            return retry(
                message =
                    "The session plan is ACTIVE. Do not finish the turn with nextStep=DONE until the persisted plan is actually DONE. " +
                            "Either continue executing, or if work completed call SessionPlanTool to mark the plan complete first.",
                loopState = loopState,
                countAsPlanToolEnforcement = true,
                maxContinuations = maxContinuations,
                maxPlanToolEnforcementAttempts = maxPlanToolEnforcementAttempts,
                effectivePlanStatus = effectivePlanStatus,
                activePlanStillHasWork = activePlanStillHasWork,
            )
        }

        val currentPlanLoopSignature =
            continuationPolicy.buildPlanLoopSignature(
                message = message,
                effectivePlanStatus = effectivePlanStatus,
                summaryText = summaryText,
            )
        val progressedLoopState = advanceLoopSignature(
            currentPlanLoopSignature = currentPlanLoopSignature,
            activePlanStillHasWork = activePlanStillHasWork,
            pendingToolOutputsEmpty = pendingToolOutputsEmpty,
            maxContinuations = maxContinuations,
            loopState = loopState,
        )
        if (progressedLoopState.retryInstruction != null) {
            return progressedLoopState.copy(
                effectivePlanStatus = effectivePlanStatus,
                activePlanStillHasWork = activePlanStillHasWork,
            )
        }

        if (planAtEvaluation.isActive() && message.nextStep?.uppercase() == "WAIT_USER") {
            val hardBlocked = continuationPolicy.isHardBlockedActivePlanResponse(message)
            if (!hardBlocked) {
                return retry(
                    message =
                        "Continue executing the ACTIVE plan autonomously. Do not ask the user questions unless truly blocked by a missing external dependency or unavailable information. Do not stop for routine confirmations such as asking permission to continue, apply safe changes, inspect files, or run the next planned step.",
                    loopState = progressedLoopState.loopState,
                    countAsPlanToolEnforcement = false,
                    maxContinuations = maxContinuations,
                    maxPlanToolEnforcementAttempts = maxPlanToolEnforcementAttempts,
                    effectivePlanStatus = effectivePlanStatus,
                    activePlanStillHasWork = activePlanStillHasWork,
                )
            }
        }

        if (activePlanStillHasWork && message.nextStep?.uppercase() != "WAIT_USER" && effectivePlanStatus != "DONE") {
            return retry(
                message =
                    "The session plan is ACTIVE and still has unchecked tasks. Continue executing until tasks are completed or you are truly blocked. Avoid asking for simple confirmations while the plan can still be executed safely.",
                loopState = progressedLoopState.loopState,
                countAsPlanToolEnforcement = false,
                maxContinuations = maxContinuations,
                maxPlanToolEnforcementAttempts = maxPlanToolEnforcementAttempts,
                effectivePlanStatus = effectivePlanStatus,
                activePlanStillHasWork = activePlanStillHasWork,
            )
        }

        return ActivePlanEvaluation(
            loopState = progressedLoopState.loopState,
            effectivePlanStatus = effectivePlanStatus,
            activePlanStillHasWork = activePlanStillHasWork,
        )
    }

    private fun retry(
        message: String,
        loopState: ActivePlanLoopState,
        countAsPlanToolEnforcement: Boolean,
        maxContinuations: Int,
        maxPlanToolEnforcementAttempts: Int,
        effectivePlanStatus: String,
        activePlanStillHasWork: Boolean,
    ): ActivePlanEvaluation {
        if (countAsPlanToolEnforcement && loopState.forcePlanToolAttempts >= maxPlanToolEnforcementAttempts) {
            return ActivePlanEvaluation(
                loopState = loopState,
                effectivePlanStatus = effectivePlanStatus,
                activePlanStillHasWork = activePlanStillHasWork,
            )
        }
        if (loopState.continuationCount >= maxContinuations) {
            return ActivePlanEvaluation(
                loopState = loopState,
                effectivePlanStatus = effectivePlanStatus,
                activePlanStillHasWork = activePlanStillHasWork,
            )
        }
        return ActivePlanEvaluation(
            retryInstruction = message,
            loopState =
                loopState.copy(
                    continuationCount = loopState.continuationCount + 1,
                    forcePlanToolAttempts =
                        if (countAsPlanToolEnforcement) loopState.forcePlanToolAttempts + 1 else loopState.forcePlanToolAttempts,
                ),
            effectivePlanStatus = effectivePlanStatus,
            activePlanStillHasWork = activePlanStillHasWork,
        )
    }

    private fun advanceLoopSignature(
        currentPlanLoopSignature: String,
        activePlanStillHasWork: Boolean,
        pendingToolOutputsEmpty: Boolean,
        maxContinuations: Int,
        loopState: ActivePlanLoopState,
    ): ActivePlanEvaluation {
        val nextLoopState =
            if (activePlanStillHasWork) {
                if (currentPlanLoopSignature == loopState.lastPlanLoopSignature) {
                    loopState.copy(repeatedPlanLoopSignatureCount = loopState.repeatedPlanLoopSignatureCount + 1)
                } else {
                    loopState.copy(
                        lastPlanLoopSignature = currentPlanLoopSignature,
                        repeatedPlanLoopSignatureCount = 0,
                    )
                }
            } else {
                loopState.copy(
                    lastPlanLoopSignature = currentPlanLoopSignature,
                    repeatedPlanLoopSignatureCount = 0,
                )
            }

        if (activePlanStillHasWork && nextLoopState.repeatedPlanLoopSignatureCount >= 1 && pendingToolOutputsEmpty) {
            return if (nextLoopState.continuationCount < maxContinuations) {
                ActivePlanEvaluation(
                    retryInstruction =
                        "You repeated the ACTIVE-plan response without making progress. Take the next concrete action now. If you are truly blocked, return WAIT_USER with one specific missing external dependency or unavailable-information question.",
                    loopState = nextLoopState.copy(continuationCount = nextLoopState.continuationCount + 1),
                    effectivePlanStatus = "",
                    activePlanStillHasWork = activePlanStillHasWork,
                )
            } else {
                ActivePlanEvaluation(
                    loopState = nextLoopState,
                    effectivePlanStatus = "",
                    activePlanStillHasWork = activePlanStillHasWork,
                )
            }
        }

        return ActivePlanEvaluation(
            loopState = nextLoopState,
            effectivePlanStatus = "",
            activePlanStillHasWork = activePlanStillHasWork,
        )
    }
}
