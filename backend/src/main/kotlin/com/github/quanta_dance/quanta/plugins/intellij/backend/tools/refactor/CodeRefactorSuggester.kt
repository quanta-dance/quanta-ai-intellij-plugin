// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.refactor

/*
CodeRefactorSuggester Class Explanation:

This tool publishes actionable refactoring suggestions to the plugin UI for review. It does not apply edits.
- Only submit suggestions that contain concrete code and valid line ranges. Purely descriptive items should be omitted.
- Applying suggestions is handled by ApplyRefactorSuggestions.
*/

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.chat.ChatConversationService
import com.github.quanta_dance.quanta.plugins.intellij.backend.models.Suggestion
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionItem
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription(
    "Publish actionable code refactoring suggestions to the UI. Non-actionable (descriptive-only) items must not be provided here.",
)
class CodeRefactorSuggester : ToolInterface<String> {
    @field:JsonPropertyDescription(
        "Only provide actionable suggestions: each item MUST include file, valid original line range (for display only)," +
            " replaced_code (exact current text), and suggested_code (replacement). Descriptive-only suggestions are not accepted.",
    )
    var suggestions: List<Suggestion> = emptyList()

    private fun isActionable(s: Suggestion): Boolean =
        s.file.isNotBlank() && s.suggested_code.isNotBlank() && s.replaced_code.isNotBlank() &&
            s.original_line_from > 0 && s.original_line_to >= s.original_line_from

    override fun execute(project: Project): String {
        if (suggestions.isEmpty()) return "No refactor suggestions available."

        val actionable = suggestions.filter { isActionable(it) }
        if (actionable.isEmpty()) return "No actionable suggestions provided."

        val chat = project.service<ChatConversationService>()
        val toolItems =
            actionable.mapIndexed { index, suggestion ->
                ToolExecutionItem(
                    callId = "refactor-${index + 1}-${suggestion.file}:${suggestion.original_line_from}-${suggestion.original_line_to}",
                    toolName = "CodeRefactorSuggester",
                    displayText = "${suggestion.file}:${suggestion.original_line_from}-${suggestion.original_line_to}",
                    status = ToolExecutionStatus.SUCCEEDED,
                    filePath = suggestion.file,
                    detailText =
                        buildString {
                            appendLine(suggestion.message)
                            appendLine()
                            appendLine("Current:")
                            appendLine(suggestion.replaced_code)
                            appendLine()
                            appendLine("Suggested:")
                            appendLine(suggestion.suggested_code)
                        }.trim(),
                )
            }
        chat.appendAiToolMessage(toolItems)

        return "Actionable refactor suggestions published (${actionable.size})."
    }
}
