// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.actions

import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendActionCatalog
import com.github.quanta_dance.quanta.plugins.intellij.frontend.settings.FrontendQuantaSettingsState
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/** Sends the selected editor code to chat with a documentation-comment instruction. */
class CommentSelectedAction : AnAction("Comment") {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val selectedText = event.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText
        if (selectedText.isNullOrBlank()) {
            notifyEditorAction(project, "Select code to document.", isError = true)
            return
        }

        val instruction =
            FrontendActionCatalog
                .actionById(FrontendQuantaSettingsState.instance.state.actionConfigsJson, "comment")
                ?.instruction
                ?: "Add helpful comments to the selected code."
        sendEditorActionPrompt(
            project = project,
            prompt =
                EditorActionPrompt.comment(
                    instruction,
                    event.getData(CommonDataKeys.VIRTUAL_FILE)?.path,
                    selectedText,
                ),
            scopeName = "comment-selected-code",
        )
    }

    override fun update(event: AnActionEvent) {
        templatePresentation.text =
            FrontendActionCatalog
                .actionById(
                    FrontendQuantaSettingsState.instance.state.actionConfigsJson,
                    "comment",
                )?.label
                ?: "Comment"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
