// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.tools

import com.intellij.openapi.project.Project

/**
 * Base interface for tools accessible by OpenAI and backend invokers.
 */
interface ToolInterface<I> {
    fun execute(project: Project): I
}
