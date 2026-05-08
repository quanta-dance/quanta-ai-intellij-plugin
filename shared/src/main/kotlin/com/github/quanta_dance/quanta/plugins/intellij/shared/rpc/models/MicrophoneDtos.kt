package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

@Serializable
data class MicrophoneTranscriptionResultDto(
    val sessionId: String,
    val transcript: String = "",
    val submitted: Boolean = false,
)

@Serializable
data class SpeechChunkDto(
    val sessionId: String,
    val chunkBase64: String = "",
    val sequence: Int = 0,
    val isLast: Boolean = false,
)

@Serializable
data class FrontendLogDto(
    val level: FrontendLogLevel = FrontendLogLevel.INFO,
    val message: String,
)

@Serializable
enum class FrontendLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}
