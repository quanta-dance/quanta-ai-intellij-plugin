package com.github.quanta_dance.quanta.plugins.intellij.shared.contracts

data class RefactorRequest(
    val filePath: String? = null,
    val selectedText: String? = null,
    val instruction: String = "Refactor selected code.",
)

data class RefactorResponse(
    val success: Boolean,
    val message: String,
)

interface RefactorService {
    suspend fun refactor(request: RefactorRequest): RefactorResponse
}
