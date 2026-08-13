// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.toolwindow

import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.chatApp
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.ChatViewModel
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.FrontendChatRepositoryModel
import com.github.quanta_dance.quanta.plugins.intellij.frontend.coroutines.CoroutineScopeHolder
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaPluginConfigurable
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.isSearching
import com.github.quanta_dance.quanta.plugins.intellij.frontend.voice.FrontendAIVoiceService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.jewel.bridge.addComposeTab

/**
 * Frontend tool-window entry point for the Quanta chat UI.
 *
 * This factory wires the Compose chat surface, voice service, and title-bar actions into the
 * frontend-only presentation layer used in local and split-mode IDE sessions.
 */
@Suppress("UnstableApiUsage")
class QuantaFrontendToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        chatApp(project, toolWindow)
    }

    private fun chatApp(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val viewModel =
            ChatViewModel(
                CoroutineScopeHolder.getInstance(project).createScope(ChatViewModel::class.java.simpleName),
                FrontendChatRepositoryModel.getInstance(project),
            )
        val voiceService = project.service<FrontendAIVoiceService>()
        Disposer.register(toolWindow.disposable, viewModel)

        toolWindow.setTitleActions(
            listOf(
                object : AnAction("New Chat", "Start a new chat session", com.intellij.icons.AllIcons.General.Add) {
                    override fun actionPerformed(e: AnActionEvent) {
                        viewModel.onCreateNewSession()
                    }
                },
                object : AnAction("Search", "Search messages", com.intellij.icons.AllIcons.Actions.Find) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val handler = viewModel.searchChatMessagesHandler()
                        if (handler.searchStateFlow.value.isSearching) {
                            handler.onStopSearch()
                        } else {
                            handler.onStartSearch()
                        }
                    }
                },
                object : AnAction("Settings", "Open settings", com.intellij.icons.AllIcons.Actions.InlayGear) {
                    override fun actionPerformed(e: AnActionEvent) {
                        ShowSettingsUtil
                            .getInstance()
                            .showSettingsDialog(project, FrontendQuantaPluginConfigurable::class.java)
                    }
                },
            ),
        )

        toolWindow.addComposeTab("Quanta AI") {
            chatApp(project, viewModel, voiceService)
        }
    }
}
