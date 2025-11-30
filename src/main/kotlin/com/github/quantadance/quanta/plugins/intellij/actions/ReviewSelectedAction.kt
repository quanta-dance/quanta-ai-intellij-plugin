// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.actions

import com.github.quantadance.quanta.plugins.intellij.services.OpenAIService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ReviewSelectedAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val file: VirtualFile? = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val project: Project? = event.project

        ApplicationManager.getApplication().executeOnPooledThread {
            project?.service<OpenAIService>()?.sendMessage("Review and suggest changes in selected code")
        }
    }

    override fun update(e: AnActionEvent) {
        // Using the event, evaluate the context,
        // and enable or disable the action.
        //  println("!!!!!")
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}
