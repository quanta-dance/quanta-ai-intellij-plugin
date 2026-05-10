// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.session

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.SessionSchedulerService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription(
    "Schedule a follow-up manager turn in this IDE session. Session-only: tasks are not persisted across IDE restarts. " +
            "Actions: ADD | LIST | CANCEL. If no specific agent is provided, the task is assigned to the main AI by default.",
)
class ScheduleTaskTool : ToolInterface<Map<String, Any>> {
    @field:JsonPropertyDescription("Action: ADD | LIST | CANCEL")
    var action: String? = null

    @field:JsonPropertyDescription("Optional job name (for ADD)")
    var name: String? = null

    @field:JsonPropertyDescription("Message to send to the manager when the job triggers (for ADD)")
    var message: String? = null

    @field:JsonPropertyDescription("Delay in seconds before first run (for ADD). If both seconds and minutes set, seconds wins.")
    var delaySeconds: Long? = null

    @field:JsonPropertyDescription("Delay in minutes before first run (for ADD)")
    var delayMinutes: Long? = null

    @field:JsonPropertyDescription(
        "Optional agent id that owns this schedule. If omitted, the task is assigned to the main AI by default. When set, scheduler enforces at most one pending one-shot job per agent.",
    )
    var agentId: String? = null

    @field:JsonPropertyDescription("Job id (for CANCEL)")
    var jobId: String? = null

    override fun execute(project: Project): Map<String, Any> {
        val svc = project.service<SessionSchedulerService>()
        val act = action?.trim()?.uppercase().orEmpty().ifBlank { "LIST" }
        return when (act) {
            "LIST" -> {
                val items =
                    svc.list().map {
                        mapOf(
                            "id" to it.id,
                            "name" to it.name,
                            "nextRunAtMs" to it.nextRunAtMs,
                            "ownerAgentId" to it.ownerAgentId,
                        )
                    }
                mapOf("status" to "ok", "jobs" to items)
            }

            "CANCEL" -> {
                val id = jobId?.trim().orEmpty()
                if (id.isBlank()) return mapOf("status" to "error", "message" to "jobId is required")
                val ok = svc.cancel(id)
                if (ok) {
                    mapOf("status" to "ok", "cancelled" to id)
                } else {
                    mapOf(
                        "status" to "error",
                        "message" to "unknown job id",
                    )
                }
            }

            "ADD" -> {
                val msg = message?.trim().orEmpty()
                if (msg.isBlank()) return mapOf("status" to "error", "message" to "message is required")
                val n = name?.trim().orEmpty().ifBlank { "job" }

                val owner = agentId?.trim().orEmpty().ifBlank { "main" }

                val delayMs =
                    when {
                        delaySeconds != null -> (delaySeconds!!).coerceAtLeast(0) * 1000L
                        delayMinutes != null -> (delayMinutes!!).coerceAtLeast(0) * 60_000L
                        else -> 0L
                    }

                val info = svc.add(n, msg, delayMs, ownerAgentId = owner)
                mapOf(
                    "status" to "ok",
                    "job" to
                            mapOf(
                                "id" to info.id,
                                "name" to info.name,
                                "nextRunAtMs" to info.nextRunAtMs,
                                "ownerAgentId" to info.ownerAgentId,
                            ),
                )
            }

            else -> mapOf("status" to "error", "message" to "Unknown action: $act")
        }
    }
}
