package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

@Serializable
data class MicrophoneTranscriptionResultDto(
    val sessionId: String,
    val transcript: String = "",
    val submitted: Boolean = false,
)
