// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.agent

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AgentManagerService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription("Read and clear inbox messages for an agent.")
class AgentReadInboxTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("Agent id whose inbox to read. If omitted, returns an error.")
    var agentId: String = ""

    @field:JsonPropertyDescription("Maximum number of messages to return (0 = all). Default: 50")
    var limit: Int = 50

    override fun execute(project: Project): Map<String, Any> {
        val svc = project.service<AgentManagerService>()
        if (agentId.isBlank()) return mapOf("status" to "error", "message" to "agentId is required")

        val msgs = svc.readAndClearInbox(agentId)
        val capped = if (limit <= 0) msgs else msgs.take(limit)

        val out =
            capped.map { m ->
                mapOf(
                    "timestamp" to m.timestamp,
                    "from" to m.from,
                    "kind" to m.kind,
                    "text" to m.text,
                )
            }

        return mapOf("status" to "ok", "agentId" to agentId, "messages" to out, "count" to out.size)
    }
}
