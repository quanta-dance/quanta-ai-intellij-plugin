// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class OpenAIService(@Suppress("unused") private val project: Project) {
    fun generateImage(promptText: String): String = "https://example.invalid/generated.png"
    fun resetThreadStatePreservingHistory() {}
    fun sendMessage(promptText: String, onDone: (String) -> Unit = {}) {
        onDone("")
    }
}
