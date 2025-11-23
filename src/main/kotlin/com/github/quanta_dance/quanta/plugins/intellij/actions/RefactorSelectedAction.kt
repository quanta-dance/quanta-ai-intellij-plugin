package com.github.quanta_dance.quanta.plugins.intellij.actions

import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class RefactorSelectedAction : AnAction() {
    companion object { private val logger = Logger.getInstance(RefactorSelectedAction::class.java) }
    override fun actionPerformed(event: AnActionEvent) {
        val file: VirtualFile? = event.getData(CommonDataKeys.VIRTUAL_FILE)
        QDLog.debug(logger) { "RefactorSelectedAction on file: ${file?.name}" }
        val project: Project? = event.project
        ApplicationManager.getApplication().executeOnPooledThread {
            project?.service<OpenAIService>()?.sendMessage(
                "Refactor selected code. If code is not selected - then refactor the whole file. If file is good enough - don't do anything." +
                        "If do refactoring, focus on correctness, comply with interfaces"
            )
        }
    }
    override fun update(e: AnActionEvent) {}
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
