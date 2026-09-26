// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.AgentChannelStateService
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.TeamDelegationCoordinatorService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AgentManagerService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Starts tracked asynchronous work for one or more independent agents and schedules one manager summary. */
@JsonClassDescription(
    "Delegate tracked asynchronous work to one or more internal agents. Quanta schedules one final manager summary after every task settles.",
)
class DelegateTeamTaskTool : ToolInterface<Map<String, Any>> {
    data class Target(
        @field:JsonPropertyDescription("Current target agent ID from the active Agents roster")
        var agentId: String = "",
        @field:JsonPropertyDescription(
            "Current target agent role from the active Agents roster. Always provide this with agentId as a stale-ID fallback.",
        )
        var agentRole: String? = null,
    )

    @field:JsonPropertyDescription("Short title for the team task")
    var title: String = "Team task"

    @field:JsonPropertyDescription("Independent task each selected agent should perform")
    var message: String = ""

    @field:JsonPropertyDescription(
        "Required exact number of distinct recipients requested by the user. It must equal the targets list size.",
    )
    var expectedTargetCount: Int = 0

    @field:JsonPropertyDescription(
        "All requested agents. Make exactly ONE DelegateTeamTaskTool call containing every independent recipient; never call this tool once per agent.",
    )
    var targets: List<Target> = emptyList()

    override fun execute(project: Project): Map<String, Any> {
        val manager = project.service<AgentManagerService>()
        val channel = project.service<AgentChannelStateService>()
        val roster = manager.getAgentsSnapshot()
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
        if (resolution.resolved.isEmpty()) {
            return mapOf(
                "status" to "error",
                "message" to "No current team targets could be resolved",
                "currentRoster" to rosterSummary(roster),
            )
        }
        if (resolution.unresolved.isNotEmpty()) {
            return mapOf(
                "status" to "error",
                "message" to "No work was started because not every requested target could be resolved",
                "unresolvedTargets" to resolution.unresolved,
                "currentRoster" to rosterSummary(roster),
            )
        }
        val tasksByAgentId =
            resolution.resolved.map { agent ->
                agent.id to
                    channel.createTask(
                        title = title,
                        requestText = message,
                        assignedAgentIds = listOf(agent.id),
                        assignedRoles = listOf(agent.role),
                        autoStart = false,
                    )
            }
        val tasks = tasksByAgentId.map { it.second }
        val coordinator = project.service<TeamDelegationCoordinatorService>()
        coordinator.register(
            title = title,
            taskRoles =
                tasksByAgentId.associate { (agentId, task) ->
                    task.id to roster.first { agent -> agent.id == agentId }.role
                },
        )
        tasksByAgentId.forEach { (agentId, task) ->
            manager.sendMessageAsync(agentId, message, task.id).whenComplete { result, error ->
                coordinator.recordTaskResult(
                    result?.copy(taskId = result.taskId ?: task.id)
                        ?: AgentManagerService.AgentTaskResult(
                            requestId = "",
                            agentId = agentId,
                            ok = false,
                            text = null,
                            error = error?.message ?: "Agent task did not start",
                            taskId = task.id,
                        ),
                )
            }
        }
        return mapOf(
            "status" to "queued",
            "taskCount" to tasks.size,
            "targetRoles" to resolution.resolved.map(AgentManagerService.AgentSnapshot::role),
            "taskIds" to tasks.map { it.id },
            "handoffToAsyncCoordination" to true,
            "message" to teamDelegationHandoffMessage(resolution.resolved.map(AgentManagerService.AgentSnapshot::role)),
        )
    }
}

internal fun teamDelegationHandoffMessage(roles: List<String>): String =
    "I asked ${formatTeamRecipients(roles)} to work independently. " +
        "I will summarize the findings after every report is ready."

internal fun formatTeamRecipients(roles: List<String>): String =
    when (roles.size) {
        0 -> "the team"
        1 -> roles.single()
        2 -> roles.joinToString(" and ")
        else -> roles.dropLast(1).joinToString(", ") + ", and " + roles.last()
    }

internal data class DelegationTargetResolution(
    val resolved: List<AgentManagerService.AgentSnapshot>,
    val unresolved: List<String>,
)

internal fun distinctTargetCount(targets: List<DelegateTeamTaskTool.Target>): Int =
    targets
        .map { target -> target.agentId.trim().ifBlank { target.agentRole.orEmpty().trim() } }
        .filter(String::isNotEmpty)
        .distinct()
        .size

internal fun hasExpectedTargetCount(
    expectedTargetCount: Int,
    targets: List<DelegateTeamTaskTool.Target>,
): Boolean = expectedTargetCount > 0 && expectedTargetCount == distinctTargetCount(targets)

/** Resolves all requested targets atomically so an incomplete team never produces a partial summary. */
internal fun resolveDelegationTargets(
    targets: List<DelegateTeamTaskTool.Target>,
    agents: List<AgentManagerService.AgentSnapshot>,
): DelegationTargetResolution {
    val resolvedIds = linkedSetOf<String>()
    val unresolved = mutableListOf<String>()
    targets.forEach { target ->
        val resolvedId = resolveDelegatedAgentId(target.agentId, target.agentRole, agents)
        if (resolvedId == null) {
            unresolved += target.agentRole?.trim()?.takeIf(String::isNotEmpty)
                ?: target.agentId.ifBlank { "<unspecified>" }
        } else {
            resolvedIds += resolvedId
        }
    }
    return DelegationTargetResolution(
        resolved = agents.filter { it.id in resolvedIds },
        unresolved = unresolved.distinct(),
    )
}

internal fun rosterSummary(agents: List<AgentManagerService.AgentSnapshot>): List<Map<String, String>> =
    agents.map { agent -> mapOf("agentId" to agent.id, "agentRole" to agent.role) }

/** Resolves a current target ID first, then safely falls back to one uniquely matched current role. */
internal fun resolveDelegatedAgentId(
    agentId: String,
    agentRole: String?,
    agents: List<AgentManagerService.AgentSnapshot>,
): String? {
    val requestedId = agentId.trim()
    agents.firstOrNull { agent -> agent.id == requestedId }?.let { return it.id }

    val requestedRole = agentRole?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return agents.filter { agent -> agent.role.equals(requestedRole, ignoreCase = true) }.singleOrNull()?.id
}
