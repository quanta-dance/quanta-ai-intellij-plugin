// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

@Serializable
enum class AgentChannelAuthorTypeDto {
    DIRECTOR,
    MANAGER,
    AGENT,
    SYSTEM,
}

@Serializable
enum class AgentChannelVisibilityDto {
    CHANNEL,
    THREAD,
    INTERNAL,
}

@Serializable
enum class DelegatedTaskStatusDto {
    QUEUED,
    RUNNING,
    BLOCKED,
    DONE,
    FAILED,
}

@Serializable
enum class AgentChannelEventKindDto {
    USER_MESSAGE,
    MANAGER_MESSAGE,
    AGENT_MESSAGE,
    DELEGATION_STARTED,
    DELEGATION_UPDATED,
    DELEGATION_COMPLETED,
    AGENT_HIRED,
    AGENT_REMOVED,
    TOOL_ACTIVITY,
    PLAN_UPDATED,
}
