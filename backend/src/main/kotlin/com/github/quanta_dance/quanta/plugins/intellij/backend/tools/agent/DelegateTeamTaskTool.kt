// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.TeamDelegationCoordinatorService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.CollaborationRouterService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationIntentDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationMessageDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationParticipantDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationParticipantKindDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.UUID

/** Starts one mixed local/ACP team delegation and schedules one manager summary after every task settles. */
@JsonClassDescription(
    "Delegate tracked asynchronous work to one or more collaboration participants. Quanta schedules one final manager summary after every task settles.",
)
class DelegateTeamTaskTool : ToolInterface<Map<String, Any>> {
    data class Target(
        @field:JsonPropertyDescription("Current session-scoped participant ID from the Collaboration roster")
        var participantId: String = "",
        @field:JsonPropertyDescription(
            "Current participant name from the Collaboration roster. Always provide it as a safe stale-ID fallback.",
        )
        var participantName: String? = null,
    )

    @field:JsonPropertyDescription("Short title for the team task")
    var title: String = "Team task"

    @field:JsonPropertyDescription("Independent task each selected participant should perform")
    var message: String = ""

    @field:JsonPropertyDescription(
        "Required exact number of distinct recipients requested by the user. It must equal the targets list size.",
    )
    var expectedTargetCount: Int = 0

    @field:JsonPropertyDescription(
        "All requested participants. Make exactly ONE DelegateTeamTaskTool call containing every independent recipient; never call this tool once per participant.",
    )
    var targets: List<Target> = emptyList()

    override fun execute(project: Project): Map<String, Any> {
        if (message.isBlank()) return error("message is required")
        val router = project.service<CollaborationRouterService>()
        val roster = router.roster()
        if (!hasExpectedTargetCount(expectedTargetCount, targets)) {
            return mapOf(
                "status" to "error",
                "message" to "No work was started because expectedTargetCount must match the distinct targets list size",
                "expectedTargetCount" to expectedTargetCount,
                "actualTargetCount" to distinctTargetCount(targets),
                "currentRoster" to rosterSummary(roster),
            )
        }
        val resolution = resolveDelegationTargets(targets, roster)
        if (resolution.unresolved.isNotEmpty() || resolution.resolved.size != expectedTargetCount) {
            return mapOf(
                "status" to "error",
                "message" to "No work was started because not every requested participant could be resolved",
                "unresolvedTargets" to resolution.unresolved,
                "currentRoster" to rosterSummary(roster),
            )
        }
        val managerParticipant =
            roster.firstOrNull { it.kind == CollaborationParticipantKindDto.MANAGER }
                ?: return error("The active chat manager is unavailable")
        val coordinator = project.service<TeamDelegationCoordinatorService>()
        val group = coordinator.begin(title, expectedTargetCount)
        val taskIds = mutableListOf<String>()
        resolution.resolved.forEach { participant ->
            val result =
                router.dispatch(
                    CollaborationMessageDto(
                        id = UUID.randomUUID().toString(),
                        sessionId = managerParticipant.id.removePrefix("manager:"),
                        senderId = managerParticipant.id,
                        recipientId = participant.id,
                        intent = CollaborationIntentDto.TASK,
                        text = message,
                        createdAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            val taskId = result.taskId
            if (result.accepted && taskId != null) {
                coordinator.registerTask(group, taskId, participant.displayName)
                taskIds += taskId
            } else {
                coordinator.registerFailedTask(
                    group,
                    "failed:${UUID.randomUUID()}",
                    participant.displayName,
                    result.message,
                )
            }
        }
        coordinator.seal(group)
        return mapOf(
            "status" to "queued",
            "taskCount" to expectedTargetCount,
            "targetNames" to resolution.resolved.map(CollaborationParticipantDto::displayName),
            "taskIds" to taskIds,
            "handoffToAsyncCoordination" to true,
            "message" to teamDelegationHandoffMessage(resolution.resolved.map(CollaborationParticipantDto::displayName)),
        )
    }

    private fun error(message: String): Map<String, Any> = mapOf("status" to "error", "message" to message)
}

internal fun teamDelegationHandoffMessage(names: List<String>): String =
    "I asked ${formatTeamRecipients(names)} to work independently. " +
        "I will summarize the findings after every report is ready."

internal fun formatTeamRecipients(names: List<String>): String =
    when (names.size) {
        0 -> "the team"
        1 -> names.single()
        2 -> names.joinToString(" and ")
        else -> names.dropLast(1).joinToString(", ") + ", and " + names.last()
    }

internal data class DelegationTargetResolution(
    val resolved: List<CollaborationParticipantDto>,
    val unresolved: List<String>,
)

internal fun distinctTargetCount(targets: List<DelegateTeamTaskTool.Target>): Int =
    targets
        .map { target -> target.participantId.trim().ifBlank { target.participantName.orEmpty().trim() } }
        .filter(String::isNotEmpty)
        .distinct()
        .size

internal fun hasExpectedTargetCount(
    expectedTargetCount: Int,
    targets: List<DelegateTeamTaskTool.Target>,
): Boolean = expectedTargetCount > 0 && expectedTargetCount == distinctTargetCount(targets)

/** Resolves all requested participants atomically so an incomplete team never produces a partial summary. */
internal fun resolveDelegationTargets(
    targets: List<DelegateTeamTaskTool.Target>,
    participants: List<CollaborationParticipantDto>,
): DelegationTargetResolution {
    val resolvedIds = linkedSetOf<String>()
    val unresolved = mutableListOf<String>()
    targets.forEach { target ->
        val resolvedId = resolveDelegatedParticipantId(target.participantId, target.participantName, participants)
        if (resolvedId == null) {
            unresolved += target.participantName?.trim()?.takeIf(String::isNotEmpty)
                ?: target.participantId.ifBlank { "<unspecified>" }
        } else {
            resolvedIds += resolvedId
        }
    }
    return DelegationTargetResolution(
        resolved = participants.filter { it.id in resolvedIds },
        unresolved = unresolved.distinct(),
    )
}

internal fun rosterSummary(participants: List<CollaborationParticipantDto>): List<Map<String, String>> =
    participants
        .filter { it.kind != CollaborationParticipantKindDto.MANAGER }
        .map { participant ->
            mapOf(
                "participantId" to participant.id,
                "displayName" to participant.displayName,
                "kind" to participant.kind.name,
            )
        }

/** Resolves a current ID first, then safely falls back to one uniquely matched participant name. */
internal fun resolveDelegatedParticipantId(
    participantId: String,
    participantName: String?,
    participants: List<CollaborationParticipantDto>,
): String? {
    val requestedId = participantId.trim()
    participants.firstOrNull { participant -> participant.id == requestedId }?.let { return it.id }
    val requestedName = participantName?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return participants.filter { it.displayName.equals(requestedName, ignoreCase = true) }.singleOrNull()?.id
}
