package com.github.quanta_dance.quanta.plugins.intellij.actions

import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

class CommentSelectedAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        //val file: VirtualFile? = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val project: Project? = event.project

        ApplicationManager.getApplication().executeOnPooledThread {
            project?.service<OpenAIService>()?.sendMessage(
                "Add comments to selected code. If nothing selected - add comments to the whole file. " +
                        "Comments should include javadoc for java files, scaladoc for scala files, godoc fo golang and etc",

                )
        }

    }

    override fun update(e: AnActionEvent) {
        // Using the event, evaluate the context,
        // and enable or disable the action.
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}
