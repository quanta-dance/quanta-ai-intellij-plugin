// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.OpenAIClientProvider
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.openai.client.OpenAIClient
import com.openai.models.images.ImageGenerateParams
import com.openai.models.images.ImageModel

/**
 * Thin image-generation helper kept separate from the chat orchestration service.
 */
@Service(Service.Level.PROJECT)
class OpenAIImageService(
    private val project: Project,
) {
    companion object {
        private val DEFAULT_IMAGE_SIZE = ImageGenerateParams.Size._1024X1024
        private val DEFAULT_IMAGE_MODEL = ImageModel.GPT_IMAGE_2
    }

    private fun requireClientReady(): OpenAIClient = OpenAIClientProvider.get(project)

    fun generateImage(
        promptText: String,
        requestedExtension: String? = null,
    ): String {
        val outputFormat = outputFormatForExtension(requestedExtension)
        val params =
            ImageGenerateParams
                .builder()
                .prompt(promptText)
                .size(DEFAULT_IMAGE_SIZE)
                .model(DEFAULT_IMAGE_MODEL)
                .outputFormat(outputFormat)
                .build()
        val image =
            requireClientReady()
                .images()
                .generate(params)
                .data()
                .orElseThrow()
                .firstOrNull()
                ?: throw IllegalStateException("Image API returned no image entries")
        extractUrl(image)?.let { return it }
        extractBase64(image)?.let { return "data:${mimeTypeFor(outputFormat)};base64,$it" }

        throw IllegalStateException("Image API returned neither url nor base64 image data")
    }

    private fun extractUrl(image: Any): String? =
        runCatching {
            val urlValue = image.javaClass.getMethod("url").invoke(image)
            when (urlValue) {
                is java.util.Optional<*> -> urlValue.orElse(null)?.toString()
                null -> null
                else -> urlValue.toString()
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun extractBase64(image: Any): String? =
        runCatching {
            val value = image.javaClass.getMethod("b64Json").invoke(image)
            when (value) {
                is java.util.Optional<*> -> value.orElse(null)?.toString()
                null -> null
                else -> value.toString()
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun outputFormatForExtension(extension: String?): ImageGenerateParams.OutputFormat =
        when (extension?.trim()?.lowercase()) {
            "jpg", "jpeg" -> ImageGenerateParams.OutputFormat.JPEG
            "webp" -> ImageGenerateParams.OutputFormat.WEBP
            else -> ImageGenerateParams.OutputFormat.PNG
        }

    private fun mimeTypeFor(outputFormat: ImageGenerateParams.OutputFormat): String =
        when (outputFormat) {
            ImageGenerateParams.OutputFormat.JPEG -> "image/jpeg"
            ImageGenerateParams.OutputFormat.WEBP -> "image/webp"
            else -> "image/png"
        }
}
