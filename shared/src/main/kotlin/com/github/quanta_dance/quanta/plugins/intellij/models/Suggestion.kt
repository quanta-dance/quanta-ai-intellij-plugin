package com.github.quanta_dance.quanta.plugins.intellij.models

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.serialization.Serializable

@JsonClassDescription("Actionable or informational refactor suggestion.")
@Serializable
data class Suggestion @JsonCreator constructor(
    @param:JsonProperty("file")
    @field:JsonPropertyDescription("Project-relative file path this suggestion targets.")
    val file: String,

    @param:JsonProperty("original_line_from")
    @field:JsonPropertyDescription("Original line range for display. Not used for applying edits.")
    val original_line_from: Int,

    @param:JsonProperty("original_line_to")
    val original_line_to: Int,

    @param:JsonProperty("suggested_code")
    @field:JsonPropertyDescription("Replacement code to apply when actionable.")
    val suggested_code: String,

    @param:JsonProperty("replaced_code")
    @field:JsonPropertyDescription("Exact code expected at the target range when actionable.")
    val replaced_code: String,

    @param:JsonProperty("message")
    @field:JsonPropertyDescription("Human-readable explanation for the suggestion.")
    val message: String,

    @param:JsonProperty("context_before")
    @field:JsonPropertyDescription("Optional context lines before the replaced_code to aid remapping if offsets shift.")
    val context_before: String? = null,

    @param:JsonProperty("context_after")
    @field:JsonPropertyDescription("Optional context lines after the replaced_code to aid remapping if offsets shift.")
    val context_after: String? = null,

    @param:JsonProperty("segment_hash")
    @field:JsonPropertyDescription("Optional hash of replaced_code to detect staleness.")
    val segment_hash: String? = null,

    @param:JsonProperty("file_version_at_suggest")
    @field:JsonPropertyDescription("Optional file modification stamp at suggestion time.")
    val file_version_at_suggest: Long? = null,
)