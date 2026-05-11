// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.contracts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CustomPromptRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CustomPromptResponse
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.CustomPromptService

class BackendCustomPromptServicePlaceholder : CustomPromptService {
    override suspend fun run(request: CustomPromptRequest): CustomPromptResponse =
        CustomPromptResponse(
            success = false,
            message = "Backend custom prompt service is not wired yet.",
        )
}
