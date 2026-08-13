// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc

import com.github.quanta_dance.quanta.plugins.intellij.models.Suggestion
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentChannelEventDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentInfoDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ApplyRefactorSuggestionResultDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatPlanStatusDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.DelegatedTaskDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.FrontendLogDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.MicrophoneTranscriptionResultDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.SpeechChunkDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.SynthesizedSpeechDto
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor

/**
 * Shared frontend-to-backend RPC surface for non-chat Quanta capabilities.
 *
 * This API groups agent/team state, plan status, speech flows, file opening, logging, and refactor
 * application so the frontend can stay presentation-focused in split-mode.
 */
@Rpc
interface QuantaBackendApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): QuantaBackendApi = RemoteApiProviderService.resolve(remoteApiDescriptor<QuantaBackendApi>())
    }

    suspend fun ping(): String

    suspend fun logFrontend(
        projectPath: String,
        entry: FrontendLogDto,
    )

    suspend fun getCurrentPlanStatus(projectPath: String): ChatPlanStatusDto

    suspend fun getCurrentAgents(projectPath: String): List<AgentInfoDto>

    suspend fun getCurrentDelegatedTasks(projectPath: String): List<DelegatedTaskDto>

    suspend fun getCurrentChannelEvents(projectPath: String): List<AgentChannelEventDto>

    suspend fun createDefaultAgentTeam(projectPath: String): List<AgentInfoDto>

    suspend fun synthesizeSpeech(
        projectPath: String,
        text: String,
    ): SynthesizedSpeechDto

    suspend fun startSpeechStream(
        projectPath: String,
        sessionId: String,
        text: String,
    )

    suspend fun pollSpeechChunk(
        projectPath: String,
        sessionId: String,
        afterSequence: Int,
    ): SpeechChunkDto

    suspend fun stopSpeech(projectPath: String)

    suspend fun startMicrophoneSession(
        projectPath: String,
        sessionId: String,
    )

    suspend fun appendMicrophoneAudioChunk(
        projectPath: String,
        sessionId: String,
        chunkBase64: String,
    )

    suspend fun finishMicrophoneSession(
        projectPath: String,
        sessionId: String,
    ): MicrophoneTranscriptionResultDto

    suspend fun cancelMicrophoneSession(
        projectPath: String,
        sessionId: String,
    )

    suspend fun openProjectFile(
        projectPath: String,
        relativePath: String,
    )

    suspend fun openProjectFileAtLine(
        projectPath: String,
        relativePath: String,
        line: Int,
    )

    suspend fun applyRefactorSuggestion(
        projectPath: String,
        suggestion: Suggestion,
    ): ApplyRefactorSuggestionResultDto
}
