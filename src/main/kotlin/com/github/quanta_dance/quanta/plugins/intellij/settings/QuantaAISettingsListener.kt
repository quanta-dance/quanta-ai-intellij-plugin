// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.settings

import com.intellij.util.messages.Topic

interface QuantaAISettingsListener {
    fun onSettingsChanged(newState: QuantaAISettingsState.QuantaAIState)

    companion object {
        val TOPIC: Topic<QuantaAISettingsListener> =
            Topic.create("QuantaAISettingsListener", QuantaAISettingsListener::class.java)
    }
}
