// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents

import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.QuantaAISessionState
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport

@Service(Service.Level.PROJECT)
class AgentInboxService(
    private val project: Project,
) {
    private val logger = Logger.getInstance(AgentInboxService::class.java)
    private val pcs = PropertyChangeSupport(this)

    fun addPropertyChangeListener(listener: PropertyChangeListener) = pcs.addPropertyChangeListener(listener)

    fun postInboxMessage(
        toAgentId: String,
        from: String?,
        text: String,
        kind: String? = "notification",
    ): Boolean {
        if (text.isBlank()) return false
        val state = QuantaAISessionState.instance.state
        val list = state.agentInboxes.getOrPut(toAgentId) { mutableListOf() }
        list.add(QuantaAISessionState.AgentInboxMessage(System.currentTimeMillis(), from, text, kind))
        if (list.size > 50) {
            repeat(list.size - 50) { if (list.isNotEmpty()) list.removeAt(0) }
        }
        pcs.firePropertyChange("agent_inbox", null, mapOf("agentId" to toAgentId, "count" to list.size))
        try {
            QDLog.debug(logger) { "Inbox post: to=$toAgentId from=${from ?: "<null>"} kind=${kind ?: "<null>"} inboxSize=${list.size}" }
            project.service<ToolWindowService>().addDebugMessage(
                "inbox_post",
                "to=$toAgentId from=${from ?: "<null>"} kind=${kind ?: "<null>"} inboxSize=${list.size}",
            )
        } catch (_: Throwable) {
        }
        return true
    }

    fun readAndClearInbox(agentId: String): List<QuantaAISessionState.AgentInboxMessage> {
        val state = QuantaAISessionState.instance.state
        val list = state.agentInboxes[agentId] ?: return emptyList()
        val out = list.toList()
        list.clear()
        pcs.firePropertyChange("agent_inbox", null, mapOf("agentId" to agentId, "count" to 0))
        return out
    }
}
