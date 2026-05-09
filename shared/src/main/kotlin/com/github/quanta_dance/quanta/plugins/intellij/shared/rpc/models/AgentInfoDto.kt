package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.Serializable

@Serializable
data class AgentInfoDto(
    val id: String,
    val role: String,
    val model: String? = null,
    val instructions: String? = null,
)
