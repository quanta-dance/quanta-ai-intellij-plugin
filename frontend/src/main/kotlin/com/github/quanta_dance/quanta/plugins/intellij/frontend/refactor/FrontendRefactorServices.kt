package com.github.quanta_dance.quanta.plugins.intellij.frontend.refactor

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.RefactorService

object FrontendRefactorServices {
    fun refactorService(): RefactorService = FrontendRefactorServiceLocalAdapter()
}
