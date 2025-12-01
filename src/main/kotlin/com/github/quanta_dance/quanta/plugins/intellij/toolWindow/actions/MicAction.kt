// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.toolWindow.actions

import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.QuantaAIService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.IconLoader.getIcon
import javax.swing.Icon

class MicAction : ToggleAction("Speak to AI", "Speak to AI via microphone", AllIcons.CodeWithMe.CwmMicOff) {
    private var selected = false
    private val logger = Logger.getInstance(MicAction::class.java)
    private var isMuted: Boolean = false

    var micOnAirIcon: Icon = getIcon("/icons/cwmMicOnAir.svg", javaClass)
    var micOnAirMuted: Icon = getIcon("/icons/cwmMicMuted.svg", javaClass)

    override fun isSelected(e: AnActionEvent): Boolean = selected

    override fun setSelected(
        e: AnActionEvent,
        sel: Boolean,
    ) {
        QDLog.debug(logger) { "MicAction setSelected=$sel" }
        selected = sel
        val svc = e.project?.service<QuantaAIService>()
        if (!selected) {
            svc?.speakEnd()
            e.presentation.icon = AllIcons.CodeWithMe.CwmMicOff
            isMuted = false
        } else {
            svc?.speakStart(
                onSilenceDetected = {
                    // If muted, prefer muted icon, else use standard listening icon
                    e.presentation.icon = if (isMuted) micOnAirMuted else AllIcons.CodeWithMe.CwmMicOn
                },
                onSpeechDetected = {
                    e.presentation.icon = if (isMuted) micOnAirMuted else micOnAirIcon
                },
                onMuteChanged = { muted ->
                    isMuted = muted
                    e.presentation.icon = if (muted) micOnAirMuted else AllIcons.CodeWithMe.CwmMicOn
                },
            )
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
