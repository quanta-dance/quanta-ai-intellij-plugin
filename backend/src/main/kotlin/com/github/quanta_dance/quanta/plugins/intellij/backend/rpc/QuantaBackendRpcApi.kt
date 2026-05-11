package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.*
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide.OpenFileInEditorTool
import com.github.quanta_dance.quanta.plugins.intellij.models.Suggestion
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import com.intellij.psi.PsiDocumentManager
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
        val audioBytes = backendProject.getService(AIVoiceService::class.java).say(text)
        return SynthesizedSpeechDto(
            audioBase64 = Base64.getEncoder().encodeToString(audioBytes),
        )
    }

    override suspend fun startSpeechStream(projectId: ProjectId, sessionId: String, text: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        backendProject.getService(AIVoiceService::class.java).startSpeechStream(sessionId, text)
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
        return backendProject.getService(AIVoiceService::class.java).pollSpeechChunk(sessionId, afterSequence)
    }

    override suspend fun stopSpeech(projectId: ProjectId) {
        val backendProject = projectId.findProjectOrNull() ?: return
        backendProject.getService(AIVoiceService::class.java).stopTalking()
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

    override suspend fun openProjectFileAtLine(projectId: ProjectId, relativePath: String, line: Int) {
        val backendProject = projectId.findProjectOrNull() ?: return
        OpenFileInEditorTool(filePath = relativePath, line = line).execute(backendProject)
    }

    override suspend fun applyRefactorSuggestion(
        projectId: ProjectId,
        suggestion: Suggestion,
    ): ApplyRefactorSuggestionResultDto {
        val backendProject = projectId.findProjectOrNull()
            ?: return ApplyRefactorSuggestionResultDto(applied = false, errorMessage = "Project not found")
        return applySuggestionDirectly(backendProject, suggestion)
    }

    private fun applySuggestionDirectly(
        project: Project,
        suggestion: Suggestion,
    ): ApplyRefactorSuggestionResultDto {
        val document =
            ReadAction.compute<com.intellij.openapi.editor.Document?, RuntimeException> {
                val basePath = project.basePath
                    ?: return@compute null
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/${suggestion.file}")
                    ?: return@compute null
                FileDocumentManager.getInstance().getDocument(virtualFile)
            } ?: return ApplyRefactorSuggestionResultDto(
                applied = false,
                errorMessage = "Document not available: ${suggestion.file}"
            )

        var result = ApplyRefactorSuggestionResultDto(applied = false, errorMessage = "Unknown apply error")
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val offsets = remapOffsets(project, suggestion, document)
                    ?: run {
                        result = ApplyRefactorSuggestionResultDto(
                            applied = false,
                            errorMessage = "Could not locate the original code segment to replace.",
                        )
                        return@runWriteCommandAction
                    }
                val start = offsets.first
                val end = offsets.second
                document.replaceString(start, end, suggestion.suggested_code)
                PsiDocumentManager.getInstance(project).commitDocument(document)

                val newStartLine = document.getLineNumber(start) + 1
                val newEndOffset = (start + suggestion.suggested_code.length).coerceAtMost(document.textLength)
                val newEndLine = document.getLineNumber(newEndOffset.coerceAtLeast(start)) + 1
                result = ApplyRefactorSuggestionResultDto(
                    applied = true,
                    newStartLine = newStartLine,
                    newEndLine = newEndLine,
                )
            }
        }
        return result
    }

    private fun remapOffsets(
        project: Project,
        suggestion: Suggestion,
        document: com.intellij.openapi.editor.Document,
    ): Pair<Int, Int>? {
        val plannedOffsets = plannedOffsets(project, suggestion, document) ?: return null
        var start = plannedOffsets.first
        var end = plannedOffsets.second
        val currentSegment = document.charsSequence.subSequence(start, end).toString()
        if (currentSegment != suggestion.replaced_code) {
            val windowStart = (start - 1000).coerceAtLeast(0)
            val windowEnd = (end + 1000).coerceAtMost(document.textLength)
            val found =
                fuzzyFind(document.charsSequence, suggestion.replaced_code, windowStart, windowEnd)
                    ?: document.charsSequence.indexOf(suggestion.replaced_code).takeIf { it >= 0 }
            if (found != null) {
                start = found
                end = found + suggestion.replaced_code.length
            } else {
                return null
            }
        }
        return start to end
    }

    private fun plannedOffsets(
        project: Project,
        suggestion: Suggestion,
        document: com.intellij.openapi.editor.Document,
    ): Pair<Int, Int>? {
        if (document.lineCount <= 0) return null
        val startLineIdx = (suggestion.original_line_from - 1).coerceAtLeast(0).coerceAtMost(document.lineCount - 1)
        val endLineIdx = (suggestion.original_line_to - 1).coerceAtLeast(0).coerceAtMost(document.lineCount - 1)
        val startOffset = document.getLineStartOffset(startLineIdx)
        val endOffset = document.getLineEndOffset(endLineIdx)
        return startOffset to endOffset
    }

    private fun fuzzyFind(
        docText: CharSequence,
        needle: String,
        windowStart: Int,
        windowEnd: Int,
    ): Int? {
        if (needle.isEmpty()) return null
        val start = windowStart.coerceAtLeast(0)
        val end = windowEnd.coerceAtMost(docText.length)
        if (start >= end) return null
        val index = docText.indexOf(needle, startIndex = start)
        return if (index in start until end) index else null
    }
}
