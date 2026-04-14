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
    @field:JsonPropertyDescription(
        "Next step for the conversation loop. One of: DONE | WAIT_USER | CONTINUE. " +
            "Use CONTINUE only when the system should immediately request another model turn without user input " +
            "(e.g., multi-step work that can proceed automatically). Use WAIT_USER when you need the user to confirm/provide info. " +
            "Use DONE when the task is complete.",
    )
    val nextStep: String? = null,
    @field:JsonPropertyDescription(
        "Optional: request specific built-in tools by class simple name (e.g., ReadFileContent, SearchInFiles, PatchFile). " +
            "Used for silent retry tool escalation when tools are not attached by default.",
    )
    val requestedTools: List<String>? = null,
    @field:JsonPropertyDescription(
        "Optional plan workflow status. Use DRAFT when proposing a plan for user approval, ACTIVE when executing, DONE when finished.",
    )
    val planStatus: String? = null,
    @field:JsonPropertyDescription("Optional plan goal text when creating/updating a plan.")
    val planGoal: String? = null,
    @field:JsonPropertyDescription("Optional plan definition of done text when creating/updating a plan.")
    val planDefinitionOfDone: String? = null,
    @field:JsonPropertyDescription("Optional full task list (without [ ] markers). Used when creating/updating a plan.")
    val planTasks: List<String>? = null,
    @field:JsonPropertyDescription("Optional: tasks completed in this turn. These tasks will be marked as [x] in plan.md.")
    val planCompletedTasks: List<String>? = null,
    @field:JsonPropertyDescription(
        "If true, the assistant is proposing a plan or is blocked and needs explicit user input. " +
            "During ACTIVE plan execution, use this only when truly blocked.",
    )
    val planNeedsUserConfirmation: Boolean? = null,
    @field:JsonPropertyDescription(
        "Optional: a single blocking question to ask the user when planNeedsUserConfirmation=true.",
    )
    val planBlockingQuestion: String? = null,
    @field:JsonPropertyDescription(
        "Optional: propose adding agents before plan activation. Applied only after user approval.",
    )
    val teamAddAgents: List<TeamAgentSpec>? = null,
    @field:JsonPropertyDescription(
        "Optional: propose removing agents by role name before plan activation. Applied only after user approval.",
    )
    val teamRemoveRoles: List<String>? = null,
)

@JsonClassDescription("Agent spec for team shaping")
data class TeamAgentSpec(
    @field:JsonPropertyDescription("Role name for the agent")
    val role: String,
    @field:JsonPropertyDescription("Optional model id override. If omitted, default is used.")
    val model: String? = null,
    @field:JsonPropertyDescription("Role-specific instructions")
    val instructions: String? = null,
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
