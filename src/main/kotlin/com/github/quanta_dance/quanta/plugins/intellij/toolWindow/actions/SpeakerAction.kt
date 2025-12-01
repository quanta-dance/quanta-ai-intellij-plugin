// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.toolWindow.actions

import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.IconLoader.getIcon
import javax.swing.Icon

/**
 * SpeakerAction toggles the voice feedback feature in the plugin settings.
 * It extends ToggleAction to change the icon state and voice setting.
 * Displays the status based on user selection whether AI will speak to the user or not.
 */
class SpeakerAction : ToggleAction("Toggle Voice Feedback", "Toggle voice feedback on or off", speakerOff) {
    companion object {
        private val speakerOff: Icon = getIcon("/icons/speakerOff.svg", javaClass)
        private val speakerOn: Icon = getIcon("/icons/speakerOn.svg", javaClass)
    }

    override fun isSelected(e: AnActionEvent): Boolean {
        if (!ApplicationManager.getApplication().service<QuantaAISettingsState>().state.voiceEnabled) {
            e.presentation.icon = speakerOff
        } else {
            e.presentation.icon = speakerOn
        }
        return ApplicationManager.getApplication().service<QuantaAISettingsState>().state.voiceEnabled
    }

    override fun setSelected(
        e: AnActionEvent,
        sel: Boolean,
    ) {
        ApplicationManager.getApplication().service<QuantaAISettingsState>().state.voiceEnabled = sel
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }
}
