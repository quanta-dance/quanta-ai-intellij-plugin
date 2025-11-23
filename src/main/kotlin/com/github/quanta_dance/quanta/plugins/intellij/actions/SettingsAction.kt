package com.github.quanta_dance.quanta.plugins.intellij.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

class SettingsAction : AnAction("Quanta AI Settings", "Open Quanta AI plugin settings", AllIcons.General.Settings) {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project? = e.project
        if (project != null) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAIPluginConfigurable::class.java)
        } else {
            ShowSettingsUtil.getInstance().showSettingsDialog(null as Project?, com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAIPluginConfigurable::class.java)
        }
    }
}
