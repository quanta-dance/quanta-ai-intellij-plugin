// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.media

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.OpenAIVideoService
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolExecutionPresentation
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolPresentationProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@JsonClassDescription(
    "Generate a short video from a text prompt. If filePath is omitted, the tool saves to a temporary system folder.",
)
class GenerateVideo :
    ToolInterface<Map<String, String>>,
    ToolPresentationProvider {
    override fun presentation(status: ToolExecutionStatus): ToolExecutionPresentation =
        ToolExecutionPresentation(
            title =
                resolvedDisplayFileName()?.let { "Generate video $it" }
                    ?: "Generate video",
        )

    @field:JsonPropertyDescription("Title for the video. 40 characters maximum")
    var videoTitle: String? = null

    @field:JsonPropertyDescription("Prompt to generate a short video from")
    var promptText: String? = null

    @field:JsonPropertyDescription(
        "Optional file path (including filename) where the video will be saved. If omitted, saves to a temporary system folder.",
    )
    var filePath: String? = null

    @field:JsonPropertyDescription("Optional video duration string. Supported values are '4', '8', and '12'.")
    var seconds: String? = null

    @field:JsonPropertyDescription("Optional video size string supported by the API, for example '1280x720'.")
    var size: String? = null

    companion object {
        private val logger = Logger.getInstance(GenerateVideo::class.java)
    }

    override fun execute(project: Project): Map<String, String> {
        QDLog.info(logger) { "Prompt to generate video: $promptText" }
        val prompt = promptText ?: throw IllegalArgumentException("promptText must be provided")
        val title = videoTitle ?: "Generated video"

        try {
            val outputPath = filePath?.trim().takeUnless { it.isNullOrBlank() } ?: defaultOutputPath(title)
            val resolved = PathUtils.resolveWithinProject(project, outputPath)
            val videoService = project.service<OpenAIVideoService>()
            val video =
                videoService.generateVideo(
                    promptText = prompt,
                    outputPath = resolved,
                    seconds = seconds,
                    size = size,
                )
            runCatching { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolved) }

            val savedPath =
                runCatching { PathUtils.relativizeToProject(project, resolved) }
                    .getOrNull()
                    ?.takeUnless { it.startsWith("../") || it == ".." }
                    ?: resolved.toString()
            val fileName = resolved.fileName.toString()
            return linkedMapOf(
                "displaySummary" to "Generate video $fileName",
                "filePath" to savedPath,
                "detailText" to "Video completed: id=${video.id()}, status=${video.status()}, progress=${video.progress()}%",
            )
        } catch (e: Exception) {
            QDLog.error(logger, { "Failed to generate or save video" }, e)
            throw RuntimeException("Failed to generate or save video: ${e.message}", e)
        }
    }

    private fun defaultOutputPath(title: String): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val baseName = sanitizeFileName(title).ifBlank { "generated-video" }
        val tempDir =
            java.nio.file.Files
                .createDirectories(
                    java.nio.file.Paths
                        .get("/var/tmp/quantadance-generated-videos"),
                )
        return tempDir.resolve("${baseName.take(40)}-$timestamp.mp4").toString()
    }

    private fun sanitizeFileName(title: String): String =
        Normalizer
            .normalize(title, Normalizer.Form.NFKC)
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .lowercase()

    private fun resolvedDisplayFileName(): String? {
        filePath
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        videoTitle
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { title -> return "${sanitizeFileName(title).ifBlank { "generated-video" }}.mp4" }

        return null
    }
}
