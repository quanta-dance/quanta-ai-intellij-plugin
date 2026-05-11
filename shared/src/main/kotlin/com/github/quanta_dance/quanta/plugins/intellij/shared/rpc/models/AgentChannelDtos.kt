// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import kotlinx.serialization.Serializable

@Serializable
data class DelegatedTaskDto(
    val id: String,
    val title: String,
    val requestText: String = "",
    val createdByAgentId: String? = null,
    val createdByRole: String? = null,
    val assignedAgentIds: List<String> = emptyList(),
    val assignedRoles: List<String> = emptyList(),
    val dependsOnTaskIds: List<String> = emptyList(),
    val status: DelegatedTaskStatusDto = DelegatedTaskStatusDto.QUEUED,
    val summary: String? = null,
    val result: String? = null,
    val relatedMessageId: String? = null,
    val relatedPlanTask: String? = null,
    val createdAtEpochMs: Long = 0,
    val updatedAtEpochMs: Long = 0,
)

@Serializable
data class AgentChannelEventDto(
    val id: String,
    val sessionId: String,
    val threadId: String? = null,
    val parentEventId: String? = null,
    val relatedTaskId: String? = null,
    val relatedMessageId: String? = null,
    val kind: AgentChannelEventKindDto,
    val authorType: AgentChannelAuthorTypeDto,
    val authorId: String? = null,
    val authorRole: String? = null,
    val visibility: AgentChannelVisibilityDto = AgentChannelVisibilityDto.CHANNEL,
    val text: String = "",
    val toolItems: List<ToolExecutionItem> = emptyList(),
    val status: DelegatedTaskStatusDto? = null,
    val createdAtEpochMs: Long,
)
