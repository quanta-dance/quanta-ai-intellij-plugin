package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.models.Suggestion
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@JsonClassDescription("Publish actionable code refactoring suggestions to the UI. Non-actionable (descriptive-only) items must not be provided here.")
class CodeRefactorSuggester : ToolInterface<String> {

    @JsonPropertyDescription(
        "Only provide actionable suggestions: each item MUST include file, valid original line range (for display only), replaced_code (exact current text), and suggested_code (replacement). Descriptive-only suggestions are not accepted."
    )
    var suggestions: List<Suggestion> = emptyList()

    private fun isActionable(s: Suggestion): Boolean =
        s.file.isNotBlank() && s.suggested_code.isNotBlank() && s.replaced_code.isNotBlank() &&
                s.original_line_from > 0 && s.original_line_to >= s.original_line_from

    override fun execute(project: Project): String {
        if (suggestions.isEmpty()) return "No refactor suggestions available."

        val actionable = suggestions.filter { isActionable(it) }
        if (actionable.isEmpty()) return "No actionable suggestions provided."

        actionable.forEach { suggestion ->
            project.service<ToolWindowService>().addSuggestions(listOf(suggestion))
        }

        return "Actionable refactor suggestions published (${actionable.size})."
    }
}

/*
CodeRefactorSuggester Class Explanation:

This tool publishes actionable refactoring suggestions to the plugin UI for review. It does not apply edits.
- Only submit suggestions that contain concrete code and valid line ranges. Purely descriptive items should be omitted.
- Applying suggestions is handled by ApplyRefactorSuggestions.
*/
