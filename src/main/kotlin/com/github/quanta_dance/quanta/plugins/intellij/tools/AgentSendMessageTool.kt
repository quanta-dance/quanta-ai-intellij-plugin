// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.AgentManagerService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription("Send a natural-language message to a specific agent and get its reply.")
class AgentSendMessageTool : ToolInterface<Map<String, Any>> {
    @JsonPropertyDescription("Target agent id returned by AgentCreateTool")
    var agentId: String = ""

    @JsonPropertyDescription("Message to send to the agent")
    var message: String = ""

    override fun execute(project: Project): Map<String, Any> {
        val svc = project.service<AgentManagerService>()
        if (!svc.exists(agentId)) return mapOf("status" to "error", "message" to "unknown agent id: $agentId")
        val reply = svc.sendMessage(agentId, message)
        return mapOf("status" to "ok", "reply" to reply)
    }
}
