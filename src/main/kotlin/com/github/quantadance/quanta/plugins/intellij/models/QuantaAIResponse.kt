// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.models

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

// Data class for the response format
@JsonClassDescription("Response")
data class OpenAIResponse(
    @JsonPropertyDescription("General message summarizing the AI response.")
    val summaryMessage: String,
    @JsonPropertyDescription("Required Audio summary suitable for TTS. This MUST be short, catchy and natural.")
    val ttsSummary: String,
)

@JsonClassDescription("Actionable or informational refactor suggestion.")
data class Suggestion(
    @JsonPropertyDescription("Project-relative file path this suggestion targets.")
    val file: String,
    @JsonPropertyDescription("Original line range for display. Not used for applying edits.")
    val original_line_from: Int,
    val original_line_to: Int,
    @JsonPropertyDescription("Replacement code to apply when actionable.")
    val suggested_code: String,
    @JsonPropertyDescription("Exact code expected at the target range when actionable.")
    val replaced_code: String,
    @JsonPropertyDescription("Human-readable explanation for the suggestion.")
    val message: String,
    @JsonPropertyDescription("Optional context lines before the replaced_code to aid remapping if offsets shift.")
    val context_before: String? = null,
    @JsonPropertyDescription("Optional context lines after the replaced_code to aid remapping if offsets shift.")
    val context_after: String? = null,
    @JsonPropertyDescription("Optional hash of replaced_code to detect staleness.")
    val segment_hash: String? = null,
    @JsonPropertyDescription("Optional file modification stamp at suggestion time.")
    val file_version_at_suggest: Long? = null,
)

@JsonClassDescription("Full file replacement modification (rarely used).")
data class Modification(
    val file: String,
    val content: String,
)
