// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai.models

// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)


import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription

// Data class for the response format
@JsonClassDescription("Response")
data class OpenAIResponse @JsonCreator constructor(
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
                "Use DONE when the task is complete."
    )
    val nextStep: String? = null,

    @param:JsonProperty("requestedTools")
    @field:JsonPropertyDescription(
        "Optional: request specific built-in tools by class simple name (e.g., ReadFileContent, SearchInFiles, PatchFile). " +
                "Used for silent retry tool escalation when tools are not attached by default."
    )
    val requestedTools: List<String>? = null,

    @param:JsonProperty("planStatus")
    @field:JsonPropertyDescription(
        "Optional reported plan workflow status. Use DRAFT when proposing a plan for user approval, ACTIVE when executing, DONE when finished. Persisted plan changes must be made explicitly via SessionPlanTool; this field alone does not mutate plan state."
    )
    val planStatus: String? = null,

    @param:JsonProperty("planGoal")
    @field:JsonPropertyDescription("Optional reported plan goal text. When changing the persisted plan, call SessionPlanTool explicitly instead of relying on this field alone.")
    val planGoal: String? = null,

    @param:JsonProperty("planDefinitionOfDone")
    @field:JsonPropertyDescription("Optional reported plan definition of done text. When changing the persisted plan, call SessionPlanTool explicitly instead of relying on this field alone.")
    val planDefinitionOfDone: String? = null,

    @param:JsonProperty("planTasks")
    @field:JsonPropertyDescription("Optional reported full task list (without [ ] markers). When changing the persisted plan, call SessionPlanTool explicitly instead of relying on this field alone.")
    val planTasks: List<String>? = null,

    @param:JsonProperty("planCompletedTasks")
    @field:JsonPropertyDescription("Optional reported tasks completed in this turn. Persist them by calling SessionPlanTool explicitly; these fields alone do not update the stored session plan state.")
    val planCompletedTasks: List<String>? = null,

    @param:JsonProperty("planNeedsUserConfirmation")
    @field:JsonPropertyDescription(
        "If true, the assistant is proposing a plan or is blocked and needs explicit user input. " +
                "During ACTIVE plan execution, use this only when truly blocked."
    )
    val planNeedsUserConfirmation: Boolean? = null,

    @param:JsonProperty("planBlockingQuestion")
    @field:JsonPropertyDescription(
        "Optional: a single blocking question to ask the user when planNeedsUserConfirmation=true."
    )
    val planBlockingQuestion: String? = null,

    @param:JsonProperty("teamAddAgents")
    @field:JsonPropertyDescription(
        "Optional: propose adding agents before plan activation. Applied only after user approval."
    )
    val teamAddAgents: List<TeamAgentSpec>? = null,

    @param:JsonProperty("teamRemoveRoles")
    @field:JsonPropertyDescription(
        "Optional: propose removing agents by role name before plan activation. Applied only after user approval."
    )
    val teamRemoveRoles: List<String>? = null,
)
