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
            QuantaAISessionState.instance.state.conversations.remove(conversationKeyForMain())
        } catch (_: Throwable) {
        }
    }

    private fun resetAgentsForNewSession() {
        try {
            project.service<AgentManagerService>().resetForNewSession()
        } catch (_: Throwable) {
        }
    }

    /**
     * Mirrors the existing branch-aware main-conversation keying used by `OpenAIService`.
     *
     * TODO: extract this conversation-key policy into a shared helper if more services need it.
     */
    private fun conversationKeyForMain(): String {
        val base = "main"
        val branch =
            try {
                val gitClass = Class.forName("git4idea.repo.GitRepositoryManager")
                val method = gitClass.getMethod("getInstance", Project::class.java)
                val mgr = method.invoke(null, project)
                val reposMethod = gitClass.getMethod("getRepositories")
                val repos = reposMethod.invoke(mgr) as java.util.List<*>
                if (repos.isNotEmpty()) {
                    val repo = repos[0]
                    val currentBranchMethod =
                        repo?.javaClass?.methods?.firstOrNull { it.name == "getCurrentBranchName" }
                    val b = currentBranchMethod?.invoke(repo) as? String
                    b?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        return if (!branch.isNullOrBlank()) "$base@$branch" else base
    }
}
