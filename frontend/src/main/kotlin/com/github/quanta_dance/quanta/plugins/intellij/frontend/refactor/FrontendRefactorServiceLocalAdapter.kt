// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.refactor

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.RefactorRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.RefactorResponse
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.RefactorService

class FrontendRefactorServiceLocalAdapter : RefactorService {
    override suspend fun refactor(request: RefactorRequest): RefactorResponse =
        RefactorResponse(
            success = false,
            message = "Refactor action is wired to the shared contract. Backend refactor execution is the next migration step.",
        )
}
