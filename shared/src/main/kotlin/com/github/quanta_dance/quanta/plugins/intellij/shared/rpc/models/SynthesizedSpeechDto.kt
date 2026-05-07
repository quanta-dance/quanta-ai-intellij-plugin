package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

@Serializable
data class SynthesizedSpeechDto(
    val audioBase64: String,
    val mimeType: String = "audio/mpeg",
)
