// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.media

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.backend.services.OpenAIImageService
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ToolExecutionStatus
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolExecutionPresentation
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolInterface
import com.github.quanta_dance.quanta.plugins.intellij.shared.tools.ToolPresentationProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.BufferedInputStream
import java.net.URI
import java.nio.file.Files
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

@JsonClassDescription(
    "Generate or edit an image. " +
            "Use sourceImagePath when modifying, redrawing, improving, or regenerating an existing image. " +
            "If the user refers to the current/open image or says update/redraw/improve this image, pass that image path as sourceImagePath. " +
            "If replacing the same image in place, set filePath to the same path as sourceImagePath.",
)
class GenerateImage :
    ToolInterface<Map<String, String>>,
    ToolPresentationProvider {
    override fun presentation(status: ToolExecutionStatus): ToolExecutionPresentation =
        ToolExecutionPresentation(
            title =
                resolvedDisplayFileName()?.let { "Generate image $it" }
                    ?: "Generate image",
        )

    @field:JsonPropertyDescription("Title for the image. 20 characters maximum")
    var imageTitle: String? = null

    @field:JsonPropertyDescription("Prompt to generate a image")
    var promptText: String? = null

    @field:JsonPropertyDescription(
        "Optional existing image path to use as input for image editing/regeneration. " +
                "Required when editing, improving, redrawing, or updating an existing image. " +
                "If the user refers to the current image/current open image, use that file path here.",
    )
    var sourceImagePath: String? = null

    @field:JsonPropertyDescription("Optional mask image path for image editing. Only used with sourceImagePath.")
    var maskPath: String? = null

    @field:JsonPropertyDescription(
        "Optional file path (including filename) where the image will be saved." +
                " Reuse the same path as sourceImagePath to overwrite the original image in place. " +
                "If omitted, the tool saves to a temporary system folder.",
    )
    var filePath: String? = null

    companion object {
        private val logger = Logger.getInstance(GenerateImage::class.java)
    }

    override fun execute(project: Project): Map<String, String> {
        QDLog.info(logger) { "Prompt to generate image: $promptText" }
        val prompt = promptText ?: throw IllegalArgumentException("promptText must be provided")
        val title = imageTitle ?: "Generated image"

        try {
            val outputPath =
                filePath?.trim().takeUnless { it.isNullOrBlank() }
                    ?: sourceImagePath?.trim().takeUnless { it.isNullOrBlank() }
                    ?: defaultOutputPath(title)
            val requestedExtension = outputPath.substringAfterLast('.', missingDelimiterValue = "").trim().lowercase()
            val openAIImageService = project.service<OpenAIImageService>()
            val imageData =
                openAIImageService.generateImage(
                    promptText = prompt,
                    requestedExtension = requestedExtension,
                    sourceImagePath = sourceImagePath,
                    maskPath = maskPath,
                )
            QDLog.info(logger) { "Image generated" }
            val resolved =
                try {
                    PathUtils.resolveWithinProject(project, outputPath)
                } catch (e: IllegalArgumentException) {
                    QDLog.warn(logger, { "Invalid path for GenerateImage: $outputPath" }, e)
                    throw e
                }

            val ioFile = resolved.toFile()
            ioFile.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }
            writeImage(ioFile.outputStream().buffered(), imageData)
            runCatching { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolved) }

            val savedPath =
                runCatching { PathUtils.relativizeToProject(project, resolved) }
                    .getOrNull()
                    ?.takeUnless { it.startsWith("../") || it == ".." }
                    ?: resolved.toString()
            val fileName = ioFile.name
            QDLog.info(logger) { "Image saved to: $savedPath" }
            return linkedMapOf(
                "displaySummary" to "Generate image $fileName",
                "filePath" to savedPath,
            )
        } catch (e: Exception) {
            QDLog.error(logger, { "Failed to generate or save image" }, e)
            throw RuntimeException("Failed to generate or save image: ${e.message}", e)
        }
    }

    private fun writeImage(
        output: java.io.OutputStream,
        imageData: String,
    ) {
        output.use { stream ->
            if (imageData.startsWith("data:image/")) {
                val base64Payload = imageData.substringAfter("base64,", missingDelimiterValue = "")
                require(base64Payload.isNotBlank()) { "Generated image data URI did not contain base64 payload" }
                stream.write(Base64.getDecoder().decode(base64Payload))
                stream.flush()
                return
            }

            BufferedInputStream(URI(imageData).toURL().openStream()).use { bis ->
                val buffer = ByteArray(8 * 1024)
                var read = bis.read(buffer)
                while (read != -1) {
                    stream.write(buffer, 0, read)
                    read = bis.read(buffer)
                }
                stream.flush()
            }
        }
    }

    private fun defaultOutputPath(title: String): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val baseName = sanitizeFileName(title).ifBlank { "generated-image" }
        val tempDir = Files.createDirectories(java.nio.file.Paths.get("/var/tmp/quantadance-generated-images"))
        return tempDir.resolve("${baseName.take(40)}-$timestamp.png").toString()
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

        imageTitle
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { title -> return "${sanitizeFileName(title).ifBlank { "generated-image" }}.png" }

        return null
    }
}
