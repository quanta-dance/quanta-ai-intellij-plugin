package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.intellij.openapi.project.Project

/**
 * Interface representing a tool accessible by OpenAI for enhancing development workflows.
 * @param <I> The type of result returned by the execute method.
 */
interface ToolInterface<I> {
    /**
     * Execute the tool's primary function using the given project context.
     * @param project The current project in which to apply the tool's functionality.
     * @return Result of executing the tool, specific to the tool's purpose.
     */
    fun execute(project: Project): I
}
