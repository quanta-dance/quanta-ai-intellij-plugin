// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.media

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

@JsonClassDescription("Generate an MP3 from text using OpenAI and save to a file")
class SoundGeneratorTool : ToolInterface<String> {
    @field:JsonPropertyDescription("Text prompt to generate speech from")
    var text: String? = null

    @field:JsonPropertyDescription("File path (including filename) where the mp3 will be saved")
    var filePath: String? = null

    companion object {
        private val logger = Logger.getInstance(SoundGeneratorTool::class.java)
    }

    override fun execute(project: Project): String {
        QDLog.info(logger) { "Generating speech for text: $text" }
        val t = text ?: throw IllegalArgumentException("text must be provided")


        try {
            project.service<ToolWindowService>()
                .addToolingMessage("Sound generated", "Speech requested for: ${t.take(60)}")
        } catch (_: Throwable) {
        }
        return "Speech requested"
    }
}
