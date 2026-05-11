package com.github.quanta_dance.quanta.plugins.intellij.frontend.prompts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CustomPromptRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CustomPromptResponse
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CustomPromptService

/**
 * Temporary frontend-local adapter for the shared custom prompt contract.
 *
 * TODO: replace this placeholder with backend/RPC-backed prompt execution once the corresponding
 * migration slice is completed.
 */
class FrontendCustomPromptServiceLocalAdapter : CustomPromptService {
    override suspend fun run(request: CustomPromptRequest): CustomPromptResponse =
        CustomPromptResponse(
            success = false,
            message = "Custom prompt action is wired to the shared contract. Backend prompt execution is the next migration step.",
        )
}
