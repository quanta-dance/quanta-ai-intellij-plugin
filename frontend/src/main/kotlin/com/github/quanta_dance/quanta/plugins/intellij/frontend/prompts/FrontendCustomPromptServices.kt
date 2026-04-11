package com.github.quanta_dance.quanta.plugins.intellij.frontend.prompts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CustomPromptService

object FrontendCustomPromptServices {
    fun customPromptService(): CustomPromptService = FrontendCustomPromptServiceLocalAdapter()
}
