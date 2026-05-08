package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

@Serializable
data class ChatSessionDto(
    val id: String,
    val title: String,
    val updatedAtEpochMs: Long,
    val isActive: Boolean = false,
)
