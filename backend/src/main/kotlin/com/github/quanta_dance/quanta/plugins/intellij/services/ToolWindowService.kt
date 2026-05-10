package com.github.quanta_dance.quanta.plugins.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class ToolWindowService(
    @Suppress("unused") private val project: Project,
) {
    class ToolExecHandle {
        fun setText(@Suppress("unused") text: String) = Unit

        fun stopSuccess() = Unit

        fun stopError(@Suppress("unused") errorText: String) = Unit
    }

    fun addToolingMessage(
        @Suppress("unused") toolName: String,
        @Suppress("unused") arguments: String,
    ) = Unit

    fun addImage(
        @Suppress("unused") title: String,
        @Suppress("unused") url: String,
    ) = Unit

    fun addSuggestions(@Suppress("unused") suggestions: List<com.github.quanta_dance.quanta.plugins.intellij.backend.models.Suggestion>?) =
        Unit

    fun addDebugMessage(
        @Suppress("unused") tag: String,
        @Suppress("unused") text: String,
    ) = Unit

    fun clear() = Unit

    fun startToolingMessage(
        @Suppress("unused") toolName: String,
        @Suppress("unused") initialText: String,
    ): ToolExecHandle = ToolExecHandle()

    fun startSpinner(@Suppress("unused") text: String): ToolExecHandle = ToolExecHandle()

    fun setToolWindowFactory(@Suppress("unused") toolPanel: Any) = Unit

    fun addUserMessage(@Suppress("unused") message: String): Any? = null
}
