// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc

import com.github.quanta_dance.quanta.plugins.intellij.models.Suggestion
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AcpAgentDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentChannelEventDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.AgentInfoDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ApplyRefactorSuggestionResultDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.ChatPlanStatusDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.CollaborationParticipantDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.DelegatedTaskDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.FrontendLogDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.LocalQuantaAcpSessionDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.McpServerStatusesDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.MicrophoneTranscriptionResultDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.QuantaAcpShareDto
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

    /** Returns the last verified ACP roster without starting a new discovery probe. */
    suspend fun getKnownAcpAgents(projectPath: String): List<AcpAgentDto>

    /** Explicitly probes configured ACP applications and endpoints, then updates the cached roster. */
    suspend fun discoverAcpAgents(projectPath: String): List<AcpAgentDto>

    /** Returns configured MCP servers and whether their synced configuration is still being applied. */
    suspend fun getMcpServerStatuses(projectPath: String): McpServerStatusesDto

    /** Retries the selected MCP server's connection/authentication flow without blocking the caller. */
    suspend fun retryMcpServerConnection(
        projectPath: String,
        serverName: String,
    ): Boolean

    suspend fun getCurrentAgents(projectPath: String): List<AgentInfoDto>

    /** Returns the session-authorized, transport-neutral manager/local/ACP collaboration roster. */
    suspend fun getCollaborationParticipants(projectPath: String): List<CollaborationParticipantDto>

    suspend fun getCurrentDelegatedTasks(projectPath: String): List<DelegatedTaskDto>

    suspend fun getCurrentChannelEvents(projectPath: String): List<AgentChannelEventDto>

    suspend fun createDefaultAgentTeam(projectPath: String): List<AgentInfoDto>

    /** Starts a localhost-only, single-use Quanta ACP share and returns its pasteable invite. */
    suspend fun createQuantaAcpShare(projectPath: String): QuantaAcpShareDto

    /** Joins a localhost Quanta ACP share and adds the paired IDE to this chat's ACP roster. */
    suspend fun joinQuantaAcpShare(
        projectPath: String,
        invite: String,
    ): QuantaAcpShareDto

    /** Returns currently shared Quanta IDE sessions for this operating-system user on localhost. */
    suspend fun getAvailableLocalQuantaAcpSessions(projectPath: String): List<LocalQuantaAcpSessionDto>

    /** Removes a stale or unwanted localhost Quanta IDE advertisement from the local session picker. */
    suspend fun forgetLocalQuantaAcpSession(
        projectPath: String,
        peerIdentity: String,
    )

    suspend fun stopQuantaAcpShare(projectPath: String)

    /** Removes a previously joined Quanta ACP peer and revokes it for the active chat. */
    suspend fun removeJoinedQuantaAcpAgent(
        projectPath: String,
        agentId: String,
    ): Boolean

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

    /** Selects a project-relative directory in the IDE Project tool window. */
    suspend fun openProjectDirectory(
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
