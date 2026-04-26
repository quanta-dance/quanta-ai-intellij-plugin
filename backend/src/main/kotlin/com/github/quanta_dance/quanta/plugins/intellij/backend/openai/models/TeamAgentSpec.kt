package com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.serialization.Serializable

@JsonClassDescription("Agent spec for team shaping")
data class TeamAgentSpec @JsonCreator constructor(
    @param:JsonProperty("role")
    @field:JsonPropertyDescription("Role name for the agent")
    val role: String,

    @param:JsonProperty("model")
    @field:JsonPropertyDescription("Optional model id override. If omitted, default is used.")
    val model: String? = null,

    @param:JsonProperty("instructions")
    @field:JsonPropertyDescription("Role-specific instructions")
    val instructions: String? = null,
)