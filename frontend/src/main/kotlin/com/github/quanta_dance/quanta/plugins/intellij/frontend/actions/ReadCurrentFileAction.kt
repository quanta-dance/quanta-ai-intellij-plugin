// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.actions

import com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel.FrontendChatRepositoryModel
import com.github.quanta_dance.quanta.plugins.intellij.frontend.contracts.FrontendWorkspaceFileReadService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.coroutines.CoroutineScopeHolder
import com.github.quanta_dance.quanta.plugins.intellij.frontend.rpc.FrontendSettingsRpcService
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.toDto
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.util.coroutines.childScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Frontend action that asks the backend to read the currently selected file and posts the result to chat.
 *
 * This is a split-mode example of a user action that crosses from frontend UI into backend-owned
 * workspace file access.
 */
class ReadCurrentFileAction : AnAction("Read Current File") {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val filePath = event.getData(CommonDataKeys.VIRTUAL_FILE)?.path ?: return
        val log = Logger.getInstance(ReadCurrentFileAction::class.java)
        val scope: CoroutineScope =
            CoroutineScopeHolder.getInstance(project).getPluginScope().childScope("read-current-file")

        scope.launch {
            try {
                val result = FrontendWorkspaceFileReadService.getInstance(project).readCurrentFile(filePath)
                val message = if (result.success) {
                    "backend: ${result.content}"
                } else {
                    "backend: ERROR: ${result.error ?: "Unknown error"}"
                }


                val set = FrontendQuantaSettingsState.instance.state.toDto()
                FrontendSettingsRpcService.getInstance(project).updateSettings(set)
                FrontendChatRepositoryModel.getInstance(project).sendMessage(message)
                log.info("Read Current File posted content to chat for $filePath")
            } catch (t: Throwable) {
                log.warn("Read Current File failed for $filePath", t)
                FrontendChatRepositoryModel.getInstance(project).sendMessage("backend: ${t.stackTraceToString()}")
            }
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
