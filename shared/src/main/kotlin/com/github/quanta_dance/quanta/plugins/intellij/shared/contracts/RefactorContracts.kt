// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

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
