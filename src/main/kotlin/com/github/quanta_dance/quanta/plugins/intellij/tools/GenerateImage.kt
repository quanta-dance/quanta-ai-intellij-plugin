// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIService
import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.net.URL

@JsonClassDescription("Generate image with provided prompt")
class GenerateImage : ToolInterface<String> {
    @field:JsonPropertyDescription("Title for the image. 20 characters maximum")
    var imageTitle: String? = null

    @field:JsonPropertyDescription("Prompt to generate a image")
    var promptText: String? = null

    @field:JsonPropertyDescription(
        "Optional file path (including filename) where the image will be saved." +
            " If omitted, only the URL is returned and shown in tool window.",
    )
    var filePath: String? = null

    companion object {
        private val logger = Logger.getInstance(GenerateImage::class.java)
    }

    override fun execute(project: Project): String {
        QDLog.info(logger) { "Prompt to generate image: $promptText" }
        val prompt = promptText ?: throw IllegalArgumentException("promptText must be provided")
        val title = imageTitle ?: "Generated image"

        try {
            val openAIService = project.service<OpenAIService>()
            val url = openAIService.generateImage(prompt)
            QDLog.info(logger) { "Image generated: $url" }

            // Show in tool window
            try {
                if (filePath == null) {
                    val toolService = project.service<ToolWindowService>()
                    toolService.addImage(title, url)
                }
            } catch (_: Throwable) {
                // ignore if tool window service not available
            }

            // If filePath provided, download and save the image
            val fp = filePath
            if (fp != null) {
                val projectBase = project.basePath ?: throw IllegalStateException("Project base path not found.")
                val resolved =
                    try {
                        PathUtils.resolveWithinProject(projectBase, fp)
                    } catch (e: IllegalArgumentException) {
                        project.service<ToolWindowService>()
                            .addToolingMessage("Save Image - rejected", e.message ?: "Invalid path")
                        QDLog.warn(logger, { "Invalid path for GenerateImage: $fp" }, e)
                        throw e
                    }

                // download the URL
                BufferedInputStream(URL(url).openStream()).use { bis ->
                    val ioFile = resolved.toFile()
                    ioFile.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }
                    FileOutputStream(ioFile).use { fos ->
                        val buffer = ByteArray(8 * 1024)
                        var read = bis.read(buffer)
                        while (read != -1) {
                            fos.write(buffer, 0, read)
                            read = bis.read(buffer)
                        }
                        fos.flush()
                    }
                }

                try {
                    val toolService = project.service<ToolWindowService>()
                    toolService.addToolingMessage("Image saved", "Saved to: $resolved")
                } catch (_: Throwable) {
                    // ignore
                }

                return resolved.toString()
            }

            return url
        } catch (e: Exception) {
            QDLog.error(logger, { "Failed to generate or save image" }, e)
            throw RuntimeException("Failed to generate or save image: ${e.message}", e)
        }
    }
}
