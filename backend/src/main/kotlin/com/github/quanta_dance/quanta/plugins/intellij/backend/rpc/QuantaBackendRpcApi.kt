package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.contracts.BackendWorkspaceFileService
import com.github.quanta_dance.quanta.plugins.intellij.services.AIVoiceService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileReadRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileWriteRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.WorkspaceFileRpcApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.SynthesizedSpeechDto
import com.intellij.openapi.components.service
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import java.util.*

class QuantaBackendRpcApi : QuantaBackendApi {
    override suspend fun ping(): String = "pong-from-backend"

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

    override suspend fun stopSpeech(projectId: ProjectId) {
        val backendProject = projectId.findProjectOrNull() ?: return
        backendProject.service<AIVoiceService>().stopTalking()
    }
}

class BackendWorkspaceFileRpcApi : WorkspaceFileRpcApi {
    private val workspaceFileService = BackendWorkspaceFileService()

    override suspend fun read(path: String): String =
        workspaceFileService.read(WorkspaceFileReadRequest(path)).content

    override suspend fun write(path: String, content: String): Boolean =
        workspaceFileService.write(WorkspaceFileWriteRequest(path, content)).success
}
