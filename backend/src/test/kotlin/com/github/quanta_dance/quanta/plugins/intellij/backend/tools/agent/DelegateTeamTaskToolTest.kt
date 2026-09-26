// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationParticipantDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationParticipantKindDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DelegateTeamTaskToolTest {
    @Test
    fun `resolves every requested participant before starting a team delegation`() {
        val resolution =
            resolveDelegationTargets(
                targets =
                    listOf(
                        DelegateTeamTaskTool.Target(
                            participantId = "stale-developer-id",
                            participantName = "Developer",
                        ),
                        DelegateTeamTaskTool.Target(participantId = "missing-reviewer-id"),
                    ),
                participants =
                    listOf(
                        participant(id = "local:developer-id", name = "Developer"),
                        participant(
                            id = "acp:reviewer-id",
                            name = "Reviewer",
                            kind = CollaborationParticipantKindDto.ACP_AGENT,
                        ),
                    ),
            )

        assertEquals(listOf("local:developer-id"), resolution.resolved.map(CollaborationParticipantDto::id))
        assertEquals(listOf("missing-reviewer-id"), resolution.unresolved)
    }

    @Test
    fun `deduplicates repeated resolved participants across local and ACP rosters`() {
        val resolution =
            resolveDelegationTargets(
                targets =
                    listOf(
                        DelegateTeamTaskTool.Target(
                            participantId = "local:developer-id",
                            participantName = "Developer",
                        ),
                        DelegateTeamTaskTool.Target(participantId = "stale-id", participantName = "Developer"),
                    ),
                participants = listOf(participant(id = "local:developer-id", name = "Developer")),
            )

        assertEquals(listOf("local:developer-id"), resolution.resolved.map(CollaborationParticipantDto::id))
        assertTrue(resolution.unresolved.isEmpty())
    }

    @Test
    fun `requires declared recipient count to match distinct participant targets`() {
        val targets =
            listOf(
                DelegateTeamTaskTool.Target(participantId = "local:developer-id", participantName = "Developer"),
                DelegateTeamTaskTool.Target(participantId = "acp:reviewer-id", participantName = "Reviewer"),
            )

        assertTrue(hasExpectedTargetCount(expectedTargetCount = 2, targets))
        assertTrue(!hasExpectedTargetCount(expectedTargetCount = 3, targets))
        assertTrue(!hasExpectedTargetCount(expectedTargetCount = 0, targets))
    }

    @Test
    fun `uses a natural handoff message for a mixed team delegation`() {
        assertEquals(
            "I asked Developer, Reviewer, and Shared Quanta · orders to work independently. " +
                "I will summarize the findings after every report is ready.",
            teamDelegationHandoffMessage(listOf("Developer", "Reviewer", "Shared Quanta · orders")),
        )
    }

    private fun participant(
        id: String,
        name: String,
        kind: CollaborationParticipantKindDto = CollaborationParticipantKindDto.LOCAL_AGENT,
    ) = CollaborationParticipantDto(
        id = id,
        displayName = name,
        kind = kind,
    )
}
