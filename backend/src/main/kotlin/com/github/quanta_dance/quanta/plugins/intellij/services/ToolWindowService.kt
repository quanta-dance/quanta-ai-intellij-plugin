// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class ToolWindowService(@Suppress("unused") private val project: Project) {
    fun addToolingMessage(
        toolName: String,
        arguments: String,
    ) {
    }

    fun startToolingMessage(
        toolName: String,
        arguments: String,
    ): SpinnerHandle {
        addToolingMessage(toolName, arguments)
        return SpinnerHandle()
    }

    fun addDebugMessage(
        tag: String,
        text: String,
    ) {
    }

    fun addSuggestions(suggestions: List<com.github.quanta_dance.quanta.plugins.intellij.models.Suggestion>) {
    }

    fun addImage(title: String, url: String) {
    }

    fun stopSuccess(text: String = "") {
    }

    fun stopError(text: String = "") {
    }

    fun setText(text: String = "") {
    }

    fun startSpinner(text: String): SpinnerHandle = SpinnerHandle()

    class SpinnerHandle {
        fun stopSuccess(text: String = "") {}
        fun stopError(text: String = "") {}
        fun setText(text: String = "") {}
    }

    fun clear() {
    }
}