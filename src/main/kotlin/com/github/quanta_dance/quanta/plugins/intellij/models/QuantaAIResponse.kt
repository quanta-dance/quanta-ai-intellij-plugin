// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.models

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

// Data class for the response format
@JsonClassDescription("Response")
data class OpenAIResponse(
    @field:JsonPropertyDescription("General message summarizing the AI response.")
    val summaryMessage: String,
    @field:JsonPropertyDescription("Required Audio summary suitable for TTS. This MUST be short, catchy and natural.")
    val ttsSummary: String,
)

@JsonClassDescription("Actionable or informational refactor suggestion.")
data class Suggestion(
    @field:JsonPropertyDescription("Project-relative file path this suggestion targets.")
    val file: String,
    @field:JsonPropertyDescription("Original line range for display. Not used for applying edits.")
    val original_line_from: Int,
    val original_line_to: Int,
    @field:JsonPropertyDescription("Replacement code to apply when actionable.")
    val suggested_code: String,
    @field:JsonPropertyDescription("Exact code expected at the target range when actionable.")
    val replaced_code: String,
    @field:JsonPropertyDescription("Human-readable explanation for the suggestion.")
    val message: String,
    @field:JsonPropertyDescription("Optional context lines before the replaced_code to aid remapping if offsets shift.")
    val context_before: String? = null,
    @field:JsonPropertyDescription("Optional context lines after the replaced_code to aid remapping if offsets shift.")
    val context_after: String? = null,
    @field:JsonPropertyDescription("Optional hash of replaced_code to detect staleness.")
    val segment_hash: String? = null,
    @field:JsonPropertyDescription("Optional file modification stamp at suggestion time.")
    val file_version_at_suggest: Long? = null,
)

@JsonClassDescription("Full file replacement modification (rarely used).")
data class Modification(
    val file: String,
    val content: String,
)
