// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages

/** Prompts for a user-authored instruction and sends it to Quanta AI chat. */
class CustomUserAction : AnAction("Custom Prompt") {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val prompt =
            Messages
                .showInputDialog(
                    project,
                    "Enter an instruction for Quanta AI:",
                    "Custom Prompt",
                    null,
                )?.trim()
                ?.takeIf(String::isNotEmpty) ?: return
        val selectedText = event.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText
        val filePath = event.getData(CommonDataKeys.VIRTUAL_FILE)?.path

        sendEditorActionPrompt(
            project = project,
            prompt = EditorActionPrompt.custom(prompt, filePath, selectedText),
            scopeName = "custom-user-prompt",
        )
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
