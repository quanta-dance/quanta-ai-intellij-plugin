// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services.openai

import com.github.quanta_dance.quanta.plugins.intellij.settings.QuantaAISettingsState
import com.openai.models.ChatModel

object ModelSelector {
    fun normalize(id: String): String {
        val cm = ChatModel.of(id)
        return try {
            cm.validate()
            cm.toString()
        } catch (_: Throwable) {
            ChatModel.GPT_5_MINI.toString()
        }
    }

    private fun rank(id: String): Int {
        val s = id.lowercase()
        return when {
            s.contains("nano") -> 0
            s.contains("mini") -> 1
            s.contains("gpt-5") -> 2
            else -> 1
        }
    }

    fun clampToMax(
        requested: String,
        max: String,
    ): String {
        val r = normalize(requested)
        val m = normalize(max)
        return if (rank(r) <= rank(m)) r else m
    }

    fun effectiveModel(currentModel: String): String {
        val settings = QuantaAISettingsState.instance.state
        val maxModel = settings.aiChatModel.ifBlank { ChatModel.GPT_5_MINI.toString() }
        return if (settings.dynamicModelEnabled == true) clampToMax(currentModel, maxModel) else normalize(maxModel)
    }

    fun initialModel(): String {
        val settings = QuantaAISettingsState.instance.state
        val dynamic = settings.dynamicModelEnabled == true
        val maxModel = settings.aiChatModel.ifBlank { ChatModel.GPT_5_MINI.toString() }
        val initial = if (dynamic) ChatModel.GPT_5_MINI.toString() else maxModel
        return normalize(initial)
    }
}
