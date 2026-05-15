// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.QuantaAISessionState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.*

/**
 * Owns OpenAI session bookkeeping for the main chat session.
 *
 * This keeps session id / last response id persistence and session reset behavior out of
 * [OpenAIService] so the service can focus on request orchestration.
 */
class OpenAISessionCoordinator(
    private val project: Project,
    private val onSessionStateReset: () -> Unit,
    private val onSessionChanged: (oldSessionId: String, newSessionId: String) -> Unit,
) {
    private val mainConversationKeyResolver = MainConversationKeyResolver(project)
    private var currentSessionId: String = UUID.randomUUID().toString()
    private var lastResponseId: String? = QuantaAISessionState.instance.state.mainLastResponseId

    fun currentSessionId(): String = currentSessionId

    fun lastResponseId(): String? = lastResponseId

    fun clearLastResponseId() {
        lastResponseId = null
        persistLastResponseId(null)
    }

    fun setLastResponseId(lastResponseId: String?) {
        this.lastResponseId = lastResponseId
        persistLastResponseId(lastResponseId)
    }

    fun newSession(): String {
        val old = currentSessionId
        currentSessionId = UUID.randomUUID().toString()
        clearLastResponseId()
        onSessionStateReset()
        clearPersistedMainConversation()
        resetAgentsForNewSession()
        onSessionChanged(old, currentSessionId)
        return currentSessionId
    }

    fun switchToSession(
        sessionId: String,
        lastResponseId: String?,
    ) {
        currentSessionId = sessionId
        setLastResponseId(lastResponseId)
        onSessionStateReset()
    }

    private fun persistLastResponseId(lastResponseId: String?) {
        QuantaAISessionState.instance.state.mainLastResponseId = lastResponseId
    }

    private fun clearPersistedMainConversation() {
        try {
            QuantaAISessionState.instance.state.conversations.remove(mainConversationKeyResolver.conversationKeyForMain())
        } catch (_: Throwable) {
        }
    }

    private fun resetAgentsForNewSession() {
        try {
            project.service<AgentManagerService>().resetForNewSession()
        } catch (_: Throwable) {
        }
    }

}
