package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.AgentManagerService
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.SessionPlanService
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide.OpenFileInEditorTool
import com.github.quanta_dance.quanta.plugins.intellij.services.*
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.*
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import kotlinx.coroutines.flow.Flow
import java.util.*

class QuantaBackendRpcApi : QuantaBackendApi {
    companion object {
        private val logger = Logger.getInstance(QuantaBackendRpcApi::class.java)
    }

    override suspend fun ping(): String = "pong-from-backend"

    override suspend fun logFrontend(projectId: ProjectId, entry: FrontendLogDto) {
        val message = "[Frontend][${entry.level}] ${entry.message}"
        when (entry.level) {
            FrontendLogLevel.DEBUG -> QDLog.debug(logger) { message }
            FrontendLogLevel.INFO -> QDLog.info(logger) { message }
            FrontendLogLevel.WARN -> QDLog.warn(logger) { message }
            FrontendLogLevel.ERROR -> QDLog.error(logger, { message }, null)
        }
    }

    override suspend fun getCurrentPlanStatus(projectId: ProjectId): ChatPlanStatusDto {
        val backendProject = projectId.findProjectOrNull() ?: return ChatPlanStatusDto()
        return SessionPlanService(backendProject).getCurrentPlanStatus()
    }

    override suspend fun getPlanStatusFlow(projectId: ProjectId): Flow<ChatPlanStatusDto> {
        val backendProject = projectId.findProjectOrNull() ?: return kotlinx.coroutines.flow.emptyFlow()
        val statusService = backendProject.service<SessionPlanStatusService>()
        val planService = SessionPlanService(backendProject)
        statusService.publish(planService.getCurrentPlanStatus())
        return statusService.statusFlow
    }

    override suspend fun getCurrentAgents(projectId: ProjectId): List<AgentInfoDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        return backendProject.service<AgentRosterService>().agentsFlow.value
    }

    override suspend fun getAgentsFlow(projectId: ProjectId): Flow<List<AgentInfoDto>> {
        val backendProject = projectId.findProjectOrNull() ?: return kotlinx.coroutines.flow.emptyFlow()
        return backendProject.service<AgentRosterService>().agentsFlow
    }

    override suspend fun getCurrentDelegatedTasks(projectId: ProjectId): List<DelegatedTaskDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        return backendProject.service<com.github.quanta_dance.quanta.plugins.intellij.backend.chat.AgentChannelStateService>().tasksFlow.value
    }

    override suspend fun getDelegatedTasksFlow(projectId: ProjectId): Flow<List<DelegatedTaskDto>> {
        val backendProject = projectId.findProjectOrNull() ?: return kotlinx.coroutines.flow.emptyFlow()
        return backendProject.service<com.github.quanta_dance.quanta.plugins.intellij.backend.chat.AgentChannelStateService>().tasksFlow
    }

    override suspend fun getCurrentChannelEvents(projectId: ProjectId): List<AgentChannelEventDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        return backendProject.service<com.github.quanta_dance.quanta.plugins.intellij.backend.chat.AgentChannelStateService>().eventsFlow.value
    }

    override suspend fun getChannelEventsFlow(projectId: ProjectId): Flow<List<AgentChannelEventDto>> {
        val backendProject = projectId.findProjectOrNull() ?: return kotlinx.coroutines.flow.emptyFlow()
        return backendProject.service<com.github.quanta_dance.quanta.plugins.intellij.backend.chat.AgentChannelStateService>().eventsFlow
    }

    override suspend fun createDefaultAgentTeam(projectId: ProjectId): List<AgentInfoDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        backendProject.service<AgentManagerService>().createDefaultTeam()
        return backendProject.service<AgentRosterService>().agentsFlow.value
    }

    override suspend fun synthesizeSpeech(
        projectId: ProjectId,
        text: String,
    ): SynthesizedSpeechDto {
        val backendProject = projectId.findProjectOrNull() ?: return SynthesizedSpeechDto(audioBase64 = "")
        val audioBytes = backendProject.service<AIVoiceService>().say(text)
        return SynthesizedSpeechDto(
            audioBase64 = Base64.getEncoder().encodeToString(audioBytes),
        )
    }

    override suspend fun startSpeechStream(projectId: ProjectId, sessionId: String, text: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        backendProject.service<AIVoiceService>().startSpeechStream(sessionId, text)
    }

    override suspend fun pollSpeechChunk(
        projectId: ProjectId,
        sessionId: String,
        afterSequence: Int,
    ): SpeechChunkDto {
        val backendProject = projectId.findProjectOrNull() ?: return SpeechChunkDto(
            sessionId = sessionId,
            sequence = afterSequence,
            isLast = true,
            chunkBase64 = "",
        )
        return backendProject.service<AIVoiceService>().pollSpeechChunk(sessionId, afterSequence)
    }

    override suspend fun stopSpeech(projectId: ProjectId) {
        val backendProject = projectId.findProjectOrNull() ?: return
        backendProject.service<AIVoiceService>().stopTalking()
    }

    override suspend fun startMicrophoneSession(projectId: ProjectId, sessionId: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        backendProject.service<SpeechToTextService>().startSession(sessionId)
    }

    override suspend fun appendMicrophoneAudioChunk(projectId: ProjectId, sessionId: String, chunkBase64: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        val chunkBytes = runCatching { Base64.getDecoder().decode(chunkBase64) }.getOrDefault(ByteArray(0))
        backendProject.service<SpeechToTextService>().appendAudioChunk(sessionId, chunkBytes)
    }

    override suspend fun finishMicrophoneSession(
        projectId: ProjectId,
        sessionId: String
    ): MicrophoneTranscriptionResultDto {
        val backendProject =
            projectId.findProjectOrNull() ?: return MicrophoneTranscriptionResultDto(sessionId = sessionId)
        return MicrophoneTranscriptionResultDto(
            sessionId = sessionId,
            transcript = backendProject.service<SpeechToTextService>().finishSession(sessionId),
        )
    }

    override suspend fun cancelMicrophoneSession(projectId: ProjectId, sessionId: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        backendProject.service<SpeechToTextService>().cancelSession(sessionId)
    }

    override suspend fun openProjectFile(projectId: ProjectId, relativePath: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        OpenFileInEditorTool(filePath = relativePath).execute(backendProject)
    }
}
