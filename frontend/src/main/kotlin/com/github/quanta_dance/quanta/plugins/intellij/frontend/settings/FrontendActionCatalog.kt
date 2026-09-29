// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON-backed catalog of configurable frontend quick actions.
 *
 * It defines the default editor actions shown to users and provides normalization/lookup helpers for
 * persisted action configurations stored in frontend settings.
 */
object FrontendActionCatalog {
    @Serializable
    data class ActionConfig(
        val id: String,
        val label: String,
        val instruction: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    val defaultActions: List<ActionConfig> =
        listOf(
            ActionConfig("review", "Review", "Review the selected code and suggest improvements."),
            ActionConfig("refactor", "Refactor", "Refactor the selected code with safe, minimal changes."),
            ActionConfig("comment", "Comment", "Add helpful comments to the selected code."),
            ActionConfig("read", "Read File", "Read the current file and summarize it."),
        )

    fun encode(actions: List<ActionConfig>): String = json.encodeToString(actions)

    fun decode(jsonValue: String?): List<ActionConfig> =
        runCatching {
            if (jsonValue.isNullOrBlank()) {
                defaultActions
            } else {
                json.decodeFromString<List<ActionConfig>>(jsonValue)
            }
        }.getOrDefault(defaultActions)

    fun actionById(
        json: String?,
        id: String,
    ): ActionConfig? = decode(json).firstOrNull { it.id == id }

    fun normalized(actions: List<ActionConfig>): List<ActionConfig> =
        actions.map {
            it.copy(
                label = it.label.take(20),
                instruction = it.instruction.trim(),
            )
        }
}
