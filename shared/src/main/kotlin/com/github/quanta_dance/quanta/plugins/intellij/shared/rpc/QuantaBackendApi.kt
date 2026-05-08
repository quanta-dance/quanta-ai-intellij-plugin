@file:Suppress("UnstableApiUsage")

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.FrontendLogDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.MicrophoneTranscriptionResultDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.SpeechChunkDto
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models.SynthesizedSpeechDto
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor

@Rpc
interface QuantaBackendApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): QuantaBackendApi {
            return RemoteApiProviderService.resolve(remoteApiDescriptor<QuantaBackendApi>())
        }
    }

    suspend fun ping(): String

    suspend fun logFrontend(projectId: ProjectId, entry: FrontendLogDto)

    suspend fun synthesizeSpeech(projectId: ProjectId, text: String): SynthesizedSpeechDto

    suspend fun startSpeechStream(projectId: ProjectId, sessionId: String, text: String)

    suspend fun pollSpeechChunk(projectId: ProjectId, sessionId: String, afterSequence: Int): SpeechChunkDto

    suspend fun stopSpeech(projectId: ProjectId)

    suspend fun startMicrophoneSession(projectId: ProjectId, sessionId: String)

    suspend fun appendMicrophoneAudioChunk(projectId: ProjectId, sessionId: String, chunkBase64: String)

    suspend fun finishMicrophoneSession(projectId: ProjectId, sessionId: String): MicrophoneTranscriptionResultDto

    suspend fun cancelMicrophoneSession(projectId: ProjectId, sessionId: String)
}