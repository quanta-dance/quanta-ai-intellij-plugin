// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class FrontendToolSettingsConfigurable : Configurable {
    private var actionConfigsJson: String = FrontendQuantaSettingsState.instance.state.actionConfigsJson

    override fun getDisplayName(): String = "QuantaDance Actions"

    override fun createComponent(): JComponent = FrontendActionEditorDialog(actionConfigsJson).createContentComponent()

    override fun isModified(): Boolean = actionConfigsJson != FrontendQuantaSettingsState.instance.state.actionConfigsJson

    override fun apply() {
        FrontendQuantaSettingsState.instance.state.actionConfigsJson = actionConfigsJson
    }

    override fun reset() {
        actionConfigsJson = FrontendQuantaSettingsState.instance.state.actionConfigsJson
    }
}
