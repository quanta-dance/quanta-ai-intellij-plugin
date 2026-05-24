// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.tools

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.intellij.openapi.project.Project

/**
 * Base interface for tools accessible by OpenAI and backend invokers.
 */
data class ToolExecutionPresentation(
    val title: String,
    val detail: String? = null,
)

interface ToolPresentationProvider {
    fun presentation(status: ToolExecutionStatus): ToolExecutionPresentation? = null
}

interface ToolInterface<I> {
    /**
     * Whether calls to this tool are safe to run concurrently with other tool calls from the same
     * model response batch.
     *
     * Keep the default conservative: tools must opt in only when they are effectively read-only and
     * do not mutate shared IDE, session, process, or filesystem state.
     */
    val canBeParallel: Boolean
        get() = false

    fun execute(project: Project): I
}
