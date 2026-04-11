package com.github.quanta_dance.quanta.plugins.intellij.backend.contracts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.RefactorRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.RefactorResponse
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.RefactorService

class BackendRefactorServicePlaceholder : RefactorService {
    override suspend fun refactor(request: RefactorRequest): RefactorResponse =
        RefactorResponse(
            success = false,
            message = "Backend refactor service is not wired yet.",
        )
}
