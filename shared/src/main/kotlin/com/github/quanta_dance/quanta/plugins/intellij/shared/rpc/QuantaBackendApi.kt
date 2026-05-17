// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

@file:Suppress("UnstableApiUsage")

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
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow

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
        projectId: ProjectId,
        entry: FrontendLogDto,
    )

    suspend fun getCurrentPlanStatus(projectId: ProjectId): ChatPlanStatusDto

    suspend fun getPlanStatusFlow(projectId: ProjectId): Flow<ChatPlanStatusDto>

    suspend fun getCurrentAgents(projectId: ProjectId): List<AgentInfoDto>

    suspend fun getAgentsFlow(projectId: ProjectId): Flow<List<AgentInfoDto>>

    suspend fun getCurrentDelegatedTasks(projectId: ProjectId): List<DelegatedTaskDto>

    suspend fun getDelegatedTasksFlow(projectId: ProjectId): Flow<List<DelegatedTaskDto>>

    suspend fun getCurrentChannelEvents(projectId: ProjectId): List<AgentChannelEventDto>

    suspend fun getChannelEventsFlow(projectId: ProjectId): Flow<List<AgentChannelEventDto>>

    suspend fun createDefaultAgentTeam(projectId: ProjectId): List<AgentInfoDto>

    suspend fun synthesizeSpeech(
        projectId: ProjectId,
        text: String,
    ): SynthesizedSpeechDto

    suspend fun startSpeechStream(
        projectId: ProjectId,
        sessionId: String,
        text: String,
    )

    suspend fun pollSpeechChunk(
        projectId: ProjectId,
        sessionId: String,
        afterSequence: Int,
    ): SpeechChunkDto

    suspend fun stopSpeech(projectId: ProjectId)

    suspend fun startMicrophoneSession(
        projectId: ProjectId,
        sessionId: String,
    )

    suspend fun appendMicrophoneAudioChunk(
        projectId: ProjectId,
        sessionId: String,
        chunkBase64: String,
    )

    suspend fun finishMicrophoneSession(
        projectId: ProjectId,
        sessionId: String,
    ): MicrophoneTranscriptionResultDto

    suspend fun cancelMicrophoneSession(
        projectId: ProjectId,
        sessionId: String,
    )

    suspend fun openProjectFile(
        projectId: ProjectId,
        relativePath: String,
    )

    suspend fun openProjectFileAtLine(
        projectId: ProjectId,
        relativePath: String,
        line: Int,
    )

    suspend fun applyRefactorSuggestion(
        projectId: ProjectId,
        suggestion: Suggestion,
    ): ApplyRefactorSuggestionResultDto
}
