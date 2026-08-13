// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AgentManagerService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription("Post an asynchronous inbox message to an agent (agent-to-agent or manager-to-agent notification).")
class AgentPostMessageTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("Target agent id")
    var toAgentId: String = ""

    @field:JsonPropertyDescription("Optional sender label (e.g., agent id, role, or 'manager')")
    var from: String? = null

    @field:JsonPropertyDescription("Message text")
    var message: String = ""

    @field:JsonPropertyDescription("Optional kind tag (e.g., 'notification', 'roster_update')")
    var kind: String? = "notification"

    override fun execute(project: Project): Map<String, Any> {
        val svc = project.service<AgentManagerService>()
        val ok = svc.postInboxMessage(toAgentId = toAgentId, from = from, text = message, kind = kind)
        return if (ok) {
            mapOf("status" to "ok", "toAgentId" to toAgentId)
        } else {
            mapOf("status" to "error", "message" to "failed to post (unknown agent or empty message)")
        }
    }
}
