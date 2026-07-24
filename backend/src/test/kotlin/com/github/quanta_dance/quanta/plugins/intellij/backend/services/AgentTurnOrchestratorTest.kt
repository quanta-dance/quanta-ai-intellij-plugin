// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.ToolExecutionService
import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models.OpenAIResponse
import com.intellij.openapi.project.Project
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.StructuredResponse
import com.openai.models.responses.StructuredResponseOutputItem
import com.openai.models.responses.StructuredResponseOutputMessage
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import kotlin.collections.ArrayDeque
import kotlin.collections.List
import kotlin.collections.any
import kotlin.collections.listOf
import kotlin.collections.mutableListOf
import kotlin.collections.plusAssign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentTurnOrchestratorTest {
    @Test
    fun `active plan rejects routine confirmation and retries autonomously`() {
        val activePlan = SessionPlan(status = "ACTIVE", tasks = listOf(SessionPlanTask("Continue work")))
        val fixture =
            orchestratorFixture(
                plan = activePlan,
                responses =
                    ArrayDeque(
                        listOf(
                            structuredResponse(
                                OpenAIResponse(
                                    summaryMessage = "Should I continue?",
                                    ttsSummary = "Need confirmation",
                                    nextStep = "WAIT_USER",
                                    planNeedsUserConfirmation = true,
                                    planBlockingQuestion = "Should I continue with the next planned step?",
                                ),
                            ),
                            structuredResponse(
                                OpenAIResponse(
                                    summaryMessage = "Blocked on a real external dependency.",
                                    ttsSummary = "Blocked",
                                    nextStep = "WAIT_USER",
                                    planNeedsUserConfirmation = true,
                                    planBlockingQuestion = "What API token should I use for staging?",
                                    blockingReasonType = "MISSING_CREDENTIAL",
                                ),
                            ),
                        ),
                    ),
            )

        fixture.orchestrator.run(mutableListOf(), null)

        assertEquals(2, fixture.createResponseCallCount)
        assertTrue(fixture.systemMessages.any { it.contains("ACTIVE plan autonomously") })
        assertEquals(listOf("Blocked on a real external dependency."), fixture.persistedMessages)
    }

    @Test
    fun `active plan allows true blocking wait user response`() {
        val activePlan = SessionPlan(status = "ACTIVE", tasks = listOf(SessionPlanTask("Continue work")))
        val fixture =
            orchestratorFixture(
                plan = activePlan,
                responses =
                    ArrayDeque(
                        listOf(
                            structuredResponse(
                                OpenAIResponse(
                                    summaryMessage = "Blocked on missing credential.",
                                    ttsSummary = "Blocked",
                                    nextStep = "WAIT_USER",
                                    planNeedsUserConfirmation = true,
                                    planBlockingQuestion = "What API token should I use for staging?",
                                    blockingReasonType = "MISSING_CREDENTIAL",
                                ),
                            ),
                        ),
                    ),
            )

        fixture.orchestrator.run(mutableListOf(), null)

        assertEquals(1, fixture.createResponseCallCount)
        assertTrue(fixture.systemMessages.isEmpty())
        assertEquals(listOf("Blocked on missing credential."), fixture.persistedMessages)
    }

    @Test
    fun `active plan nextStep done is retried until persisted plan is done`() {
        val activePlan = SessionPlan(status = "ACTIVE", tasks = listOf(SessionPlanTask("Continue work")))
        val donePlan =
            activePlan.copy(status = "DONE", tasks = listOf(SessionPlanTask("Continue work", completed = true)))
        val fixture =
            orchestratorFixture(
                planSequence = ArrayDeque(listOf(activePlan, activePlan, donePlan)),
                responses =
                    ArrayDeque(
                        listOf(
                            structuredResponse(
                                OpenAIResponse(
                                    summaryMessage = "All work is complete.",
                                    ttsSummary = "Done",
                                    nextStep = "DONE",
                                ),
                            ),
                            structuredResponse(
                                OpenAIResponse(
                                    summaryMessage = "Persisted plan is now done.",
                                    ttsSummary = "Done",
                                    nextStep = "DONE",
                                ),
                            ),
                        ),
                    ),
            )

        fixture.orchestrator.run(mutableListOf(), null)

        assertEquals(2, fixture.createResponseCallCount)
        assertTrue(fixture.systemMessages.any { it.contains("persisted plan is actually DONE") })
        assertEquals(listOf("Persisted plan is now done."), fixture.persistedMessages)
    }

    @Test
    fun `repeated no progress response triggers deterministic retry path`() {
        val activePlan = SessionPlan(status = "ACTIVE", tasks = listOf(SessionPlanTask("Continue work")))
        val fixture =
            orchestratorFixture(
                plan = activePlan,
                responses =
                    ArrayDeque(
                        listOf(
                            structuredResponse(
                                OpenAIResponse(
                                    summaryMessage = "Continuing execution.",
                                    ttsSummary = "Continuing",
                                    nextStep = "CONTINUE",
                                ),
                            ),
                            structuredResponse(
                                OpenAIResponse(
                                    summaryMessage = "Continuing execution.",
                                    ttsSummary = "Continuing",
                                    nextStep = "CONTINUE",
                                ),
                            ),
                            structuredResponse(
                                OpenAIResponse(
                                    summaryMessage = "Blocked on external approval.",
                                    ttsSummary = "Blocked",
                                    nextStep = "WAIT_USER",
                                    planNeedsUserConfirmation = true,
                                    planBlockingQuestion = "Which deployment environment should I use?",
                                    blockingReasonType = "USER_DECISION_REQUIRED",
                                ),
                            ),
                        ),
                    ),
            )

        fixture.orchestrator.run(mutableListOf(), null)

        assertEquals(3, fixture.createResponseCallCount)
        assertTrue(fixture.systemMessages.any { it.contains("still has unchecked tasks") })
        assertTrue(fixture.systemMessages.any { it.contains("without making progress") })
        assertEquals(listOf("Blocked on external approval."), fixture.persistedMessages)
    }

    private fun orchestratorFixture(
        responses: ArrayDeque<StructuredResponse<OpenAIResponse>>,
        plan: SessionPlan? = null,
        planSequence: ArrayDeque<SessionPlan>? = null,
    ): OrchestratorFixture {
        val project = mockk<Project>()
        val planService = mockk<SessionPlanService>()
        val toolExecutionService = mockk<ToolExecutionService>(relaxed = true)
        val persistedMessages = mutableListOf<String>()
        val systemMessages = mutableListOf<String>()
        var createResponseCallCount = 0
        val plans = planSequence ?: ArrayDeque(listOf(plan ?: SessionPlan()))

        every { project.getService(SessionPlanService::class.java) } returns planService
        every { project.getService(ToolExecutionService::class.java) } returns toolExecutionService
        every { planService.isActive() } answers { currentPlan(plans).isActive() }
        every { planService.loadPlanSnapshot() } answers { currentPlan(plans) }

        val orchestrator =
            AgentTurnOrchestrator(
                project = project,
                contextInjector = mockk(relaxed = true),
                toolExecutionPresenter = ToolExecutionPresenter(null, ObjectMapper()),
                continuationPolicy = AgentTurnContinuationPolicy(),
                createResponse = { _, _, _, _, _, _, _, _ ->
                    createResponseCallCount += 1
                    val response = responses.removeFirst()
                    if (plans.size > 1) {
                        plans.removeFirst()
                    }
                    response to "resp-$createResponseCallCount"
                },
                systemMessage = { text ->
                    systemMessages += text
                    mockk<ResponseInputItem>(relaxed = true)
                },
                persistAndShow = { _, _, text -> persistedMessages += text },
            )

        return OrchestratorFixture(
            orchestrator = orchestrator,
            persistedMessages = persistedMessages,
            systemMessages = systemMessages,
            createResponseCallCountProvider = { createResponseCallCount },
        )
    }

    private fun currentPlan(plans: ArrayDeque<SessionPlan>): SessionPlan = plans.firstOrNull() ?: SessionPlan()

    private fun structuredResponse(message: OpenAIResponse): StructuredResponse<OpenAIResponse> {
        val content = mockk<StructuredResponseOutputMessage.Content<OpenAIResponse>>()
        every { content.asOutputText() } returns message

        val messageItem = mockk<StructuredResponseOutputMessage<OpenAIResponse>>()
        every { messageItem.content() } returns listOf(content)

        val outputItem = mockk<StructuredResponseOutputItem<OpenAIResponse>>()
        every { outputItem.isReasoning() } returns false
        every { outputItem.isFunctionCall() } returns false
        every { outputItem.isMessage() } returns true
        every { outputItem.message() } returns Optional.of(messageItem)

        val structuredResponse = mockk<StructuredResponse<OpenAIResponse>>()
        every { structuredResponse.output() } returns listOf(outputItem)
        return structuredResponse
    }

    private data class OrchestratorFixture(
        val orchestrator: AgentTurnOrchestrator,
        val persistedMessages: List<String>,
        val systemMessages: List<String>,
        val createResponseCallCountProvider: () -> Int,
    ) {
        val createResponseCallCount: Int
            get() = createResponseCallCountProvider()
    }
}
