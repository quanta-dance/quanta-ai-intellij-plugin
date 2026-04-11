package com.github.quanta_dance.quanta.plugins.intellij.frontend.toolwindow

import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.ChatApp
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.ChatViewModel
import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.FrontendChatRepositoryModel
import com.github.quanta_dance.quanta.plugins.intellij.frontend.coroutines.CoroutineScopeHolder
import com.github.quanta_dance.quanta.plugins.intellij.frontend.voice.FrontendAIVoiceService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import org.jetbrains.jewel.bridge.addComposeTab

class QuantaFrontendToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        chatApp(project, toolWindow)
    }

    private fun chatApp(project: Project, toolWindow: ToolWindow) {
        val viewModel = ChatViewModel(
            CoroutineScopeHolder.getInstance(project).createScope(ChatViewModel::class.java.simpleName),
            FrontendChatRepositoryModel.getInstance(project)
        )
        val voiceService = project.service<FrontendAIVoiceService>()
        Disposer.register(toolWindow.disposable, viewModel)

        toolWindow.addComposeTab("Quanta AI") {
            ChatApp(project, viewModel, voiceService)
        }

    }
}