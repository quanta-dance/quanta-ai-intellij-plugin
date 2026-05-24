// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription

// Data class for the response format
@JsonClassDescription("Response")
data class OpenAIResponse
    @JsonCreator
    constructor(
        @param:JsonProperty("summaryMessage")
        @field:JsonPropertyDescription("General message summarizing the AI response.")
        val summaryMessage: String,
        @param:JsonProperty("ttsSummary")
        @field:JsonPropertyDescription("Required Audio summary suitable for TTS. This MUST be short, catchy and natural.")
        val ttsSummary: String,
        @param:JsonProperty("nextStep")
        @field:JsonPropertyDescription(
            "Next step for the conversation loop. One of: DONE | WAIT_USER | CONTINUE. " +
                "Use CONTINUE only when the system should immediately request another model turn without user input " +
                "(e.g., multi-step work that can proceed automatically). Use WAIT_USER when you need the user to confirm/provide info. " +
                "Use DONE when the task is complete.",
        )
        val nextStep: String? = null,
        @param:JsonProperty("requestedTools")
        @field:JsonPropertyDescription(
            "Optional: request specific built-in tools by class simple name (e.g., ReadFileContent, SearchInFiles, PatchFile). " +
                "Used for silent retry tool escalation when tools are not attached by default.",
        )
        val requestedTools: List<String>? = null,
        @param:JsonProperty("planNeedsUserConfirmation")
        @field:JsonPropertyDescription(
            "During ACTIVE plan execution, set this only when truly blocked and explicit user input is required. " +
                "Do not use it for routine confirmations.",
        )
        val planNeedsUserConfirmation: Boolean? = null,
        @param:JsonProperty("planBlockingQuestion")
        @field:JsonPropertyDescription(
            "Optional: a single blocking question to ask the user when planNeedsUserConfirmation=true.",
        )
        val planBlockingQuestion: String? = null,
        @param:JsonProperty("blockingReasonType")
        @field:JsonPropertyDescription(
            "Optional explicit blocking reason when nextStep=WAIT_USER during ACTIVE plan execution. Approved values: MISSING_EXTERNAL_INFO, MISSING_CREDENTIAL, USER_DECISION_REQUIRED, TOOL_FAILURE_REQUIRES_USER. Prefer this over heuristic-only blocking questions.",
        )
        val blockingReasonType: String? = null,
    )
