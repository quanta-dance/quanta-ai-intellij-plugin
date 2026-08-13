// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.prompts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CustomPromptService

/**
 * Frontend access point for custom-prompt related services.
 */
object FrontendCustomPromptServices {
    fun customPromptService(): CustomPromptService = FrontendCustomPromptServiceLocalAdapter()
}
