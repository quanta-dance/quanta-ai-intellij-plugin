// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents.AgentInboxService
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents.AgentLifecycleService
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents.AgentRegistryService
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents.AgentWakeService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Small backend facade exposing the agent-related chat services as a cohesive entry point.
 *
 * This keeps higher-level chat features from wiring individual agent services repeatedly.
 */
@Service(Service.Level.PROJECT)
class ChatBackendFacade(
    private val project: Project,
) {
    fun registry(): AgentRegistryService = project.service()

    fun inbox(): AgentInboxService = project.service()

    fun lifecycle(): AgentLifecycleService = project.service()

    fun wake(): AgentWakeService = project.service()
}
