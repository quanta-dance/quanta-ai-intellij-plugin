package com.github.quanta_dance.quanta.plugins.intellij.frontend.prompts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CustomPromptRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CustomPromptResponse
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CustomPromptService

class FrontendCustomPromptServiceLocalAdapter : CustomPromptService {
    override suspend fun run(request: CustomPromptRequest): CustomPromptResponse =
        CustomPromptResponse(
            success = false,
            message = "Custom prompt action is wired to the shared contract. Backend prompt execution is the next migration step.",
        )
}
