package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatPlanStatusDto(
    val status: String = "",
    val text: String = "",
)
