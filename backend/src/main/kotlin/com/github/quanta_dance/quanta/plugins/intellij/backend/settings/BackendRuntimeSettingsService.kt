// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.settings

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaSettingsDto
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service

/**
 * In-memory runtime settings snapshot pushed from the frontend.
 *
 * This app-level service holds effective config values that backend services need at runtime but
 * that should not be persisted on the backend machine.
 *
 * TODO: backend services still start with defaults until frontend startup sync arrives in split-mode.
 * If that proves risky, introduce an explicit "settings synchronized" readiness signal.
 */
@Service(Service.Level.APP)
class BackendRuntimeSettingsService {
    data class State(
        var openAiUrl: String = "https://api.openai.com/v1/",
        var openAiToken: String = "",
        var model: String = "gpt-5-nano",
        var aiChatModel: String = "gpt-5-nano",
        var voiceEnabled: Boolean = true,
        var voiceByLocalTTS: Boolean = false,
        var maxTokens: Long? = 2048,
        var dynamicModelEnabled: Boolean? = false,
        var agenticEnabled: Boolean? = true,
        var extraInstructions: String? = "",
        var debugEnabled: Boolean = false,
        var maxAutomaticTurns: Int = 10,
        var terminalToolEnabled: Boolean? = false,
        var terminalAllowedCommandsCsv: String = "git status,git diff,git add,git commit",
        var mcpServersJson: String = "",
    )

    data class Snapshot(
        val state: State,
        val hasFrontendSync: Boolean,
    )

    companion object {
        val instance: BackendRuntimeSettingsService
            get() = ApplicationManager.getApplication().getService(BackendRuntimeSettingsService::class.java)
    }

    @Volatile
    private var state: State = State()

    @Volatile
    private var hasFrontendSync: Boolean = false

    val settings: State
        get() = state

    fun snapshot(): Snapshot = Snapshot(state = state, hasFrontendSync = hasFrontendSync)

    fun hasFrontendSync(): Boolean = hasFrontendSync

    fun requireFrontendSync(operationName: String) {
        check(hasFrontendSync) {
            "Quanta settings have not been synchronized from the frontend yet. " +
                "$operationName must wait for frontend startup sync before using backend runtime settings."
        }
    }

    fun updateFrom(settings: QuantaSettingsDto) {
        state =
            state.copy(
                openAiUrl = settings.openAiUrl,
                openAiToken = settings.openAiToken,
                model = settings.model,
                aiChatModel = settings.aiChatModel,
                voiceEnabled = settings.voiceEnabled,
                voiceByLocalTTS = settings.voiceByLocalTTS,
                maxTokens = settings.maxTokens,
                dynamicModelEnabled = settings.dynamicModelEnabled,
                agenticEnabled = settings.agenticEnabled,
                extraInstructions = settings.extraInstructions,
                debugEnabled = settings.debugEnabled,
                maxAutomaticTurns = settings.maxAutomaticTurns,
                terminalToolEnabled = settings.terminalToolEnabled,
                terminalAllowedCommandsCsv = settings.terminalAllowedCommandsCsv,
                mcpServersJson = settings.mcpServersJson,
            )
        hasFrontendSync = true
    }
}
