// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.NestedAgentTaskCoordinatorService
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationIntentDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Sends one transport-neutral collaboration message to a participant from the current roster.
 *
 * The sender identity and active parent-task correlation are established by the executing agent
 * context, never by model-provided IDs. TASK creates asynchronous tracked work; NOTIFICATION is
 * fire-and-forget; RESULT and STATUS are correlated updates for a manager participant; CANCEL
 * requests cancellation of a known routed task.
 */
@JsonClassDescription(
    "Send a collaboration message to a roster participant. Choose recipientId from the current collaboration roster and intent based on the required lifecycle.",
)
class AgentPostMessageTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("Session-scoped participant ID from the current collaboration roster")
    var recipientId: String = ""

    @field:JsonPropertyDescription(
        "NOTIFICATION for fire-and-forget information, TASK for tracked asynchronous work with a result, RESULT or STATUS for correlated manager updates, or CANCEL for a routed task",
    )
    var intent: CollaborationIntentDto = CollaborationIntentDto.NOTIFICATION

    @field:JsonPropertyDescription("Message text; required except for CANCEL")
    var message: String = ""

    @field:JsonPropertyDescription("Task ID required only for CANCEL")
    var taskId: String? = null

    override fun execute(project: Project): Map<String, Any> {
        if (recipientId.isBlank()) return error("recipientId is required from the current collaboration roster")
        if (intent != CollaborationIntentDto.CANCEL && message.isBlank()) return error("message is required")
        val result =
            project
                .service<NestedAgentTaskCoordinatorService>()
                .dispatchFromActiveParent(recipientId, intent, message, taskId)
        return buildMap {
            put("status", if (result.accepted) "accepted" else "error")
            put("intent", intent.name)
            put("message", result.message)
            result.taskId?.let { put("taskId", it) }
        }
    }

    private fun error(message: String): Map<String, Any> = mapOf("status" to "error", "message" to message)
}
