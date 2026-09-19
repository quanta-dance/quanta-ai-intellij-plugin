// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.system

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

/**
 * Owns the Quanta terminal console for one IntelliJ project.
 *
 * Terminal jobs are project-scoped, so their output must never share a console with another open
 * project. The service keeps that UI state local to [project] and disposes the console with it.
 */
@Service(Service.Level.PROJECT)
class TerminalCommandConsoleService(
    private val project: Project,
) : Disposable {
    @Volatile
    private var consoleView: ConsoleView? = null

    fun show() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val terminalToolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            terminalToolWindow?.show()
            val contentManager = terminalToolWindow?.contentManager ?: return@invokeLater
            val existingContent = contentManager.findContent(CONTENT_DISPLAY_NAME)
            val replaceExistingContent = existingContent != null && consoleView == null
            if (replaceExistingContent) {
                contentManager.removeContent(existingContent, true)
            }
            val console = consoleView ?: createConsole().also { consoleView = it }

            if (existingContent == null || replaceExistingContent) {
                val content = ContentFactory.getInstance().createContent(console.component, CONTENT_DISPLAY_NAME, false)
                contentManager.addContent(content)
                contentManager.setSelectedContent(content)
            } else {
                contentManager.setSelectedContent(existingContent)
            }
        }
    }

    fun append(
        text: String,
        isError: Boolean,
    ) {
        val contentType =
            if (isError) {
                ConsoleViewContentType.ERROR_OUTPUT
            } else {
                ConsoleViewContentType.NORMAL_OUTPUT
            }
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                consoleView?.print(text, contentType)
            }
        }
    }

    override fun dispose() {
        consoleView?.dispose()
        consoleView = null
    }

    private fun createConsole(): ConsoleView =
        TextConsoleBuilderFactory
            .getInstance()
            .createBuilder(project)
            .apply { setViewer(true) }
            .console

    private companion object {
        private const val CONTENT_DISPLAY_NAME = "Quanta AI"
    }
}
