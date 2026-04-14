// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat.agents

import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class AgentWakeService(
    private val project: Project,
) {
    private val logger = Logger.getInstance(AgentWakeService::class.java)
    private val pcs = PropertyChangeSupport(this)
    private val lastWakeRequestedAtMs = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, AtomicBoolean>()

    fun addPropertyChangeListener(listener: PropertyChangeListener) = pcs.addPropertyChangeListener(listener)

    fun getLastWakeRequestedAtMs(agentId: String): Long? = lastWakeRequestedAtMs[agentId]

    fun requestWakeIfIdle(
        agentId: String,
        sessionProvider: () -> AgentRegistryService.AgentSession?,
        sendTurn: (String, String) -> String,
    ) {
        val now = System.currentTimeMillis()
        val last = lastWakeRequestedAtMs[agentId] ?: 0L
        if (now - last < 500L) {
            QDLog.debug(logger) { "Wake skipped (debounce): agent=$agentId now=$now last=$last" }
            return
        }
        lastWakeRequestedAtMs[agentId] = now
        pcs.firePropertyChange("agent_wake_requested", null, mapOf("agentId" to agentId, "at" to now))
        try {
            project.service<ToolWindowService>().addDebugMessage("wake_requested", "agent=$agentId at=$now")
        } catch (_: Throwable) {
        }
        if (ApplicationManager.getApplication().isUnitTestMode) return
        sessionProvider() ?: return
        val flag = inFlight.computeIfAbsent(agentId) { AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) return
        try {
            project.service<ToolWindowService>().addDebugMessage("wake_start", "agent=$agentId")
            val reply = sendTurn(
                agentId,
                "(auto) You have new inbox messages. Process them. If you need to respond to another agent, use AgentPostMessageTool. If nothing is required, reply with DONE.",
            )
            QDLog.debug(logger) { "Wake turn finished: agent=$agentId replyLen=${reply.length}" }
            project.service<ToolWindowService>().addDebugMessage("wake_done", "agent=$agentId replyLen=${reply.length}")
        } catch (t: Throwable) {
            QDLog.warn(logger, { "Wake turn failed: agent=$agentId err=${t.message}" }, t)
            project.service<ToolWindowService>().addDebugMessage("wake_error", "agent=$agentId err=${t.message}")
        } finally {
            flag.set(false)
        }
    }
}
