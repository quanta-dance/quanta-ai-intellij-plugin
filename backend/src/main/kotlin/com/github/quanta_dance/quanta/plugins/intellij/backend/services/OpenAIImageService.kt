// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.OpenAIClientProvider
import com.github.quanta_dance.quanta.plugins.intellij.backend.tools.PathUtils
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.openai.client.OpenAIClient
import com.openai.core.MultipartField
import com.openai.models.images.Image
import com.openai.models.images.ImageEditParams
import com.openai.models.images.ImageGenerateParams
import com.openai.models.images.ImageModel
import java.io.InputStream
import java.nio.file.Files

/**
 * Thin image-generation helper kept separate from the chat orchestration service.
 */
@Service(Service.Level.PROJECT)
class OpenAIImageService(
    private val project: Project,
) {
    companion object {
        private val DEFAULT_GENERATE_IMAGE_SIZE = ImageGenerateParams.Size._1024X1024
        private val DEFAULT_EDIT_IMAGE_SIZE = ImageEditParams.Size._1024X1024
        private val DEFAULT_GENERATE_IMAGE_MODEL = ImageModel.GPT_IMAGE_2
        private val DEFAULT_EDIT_IMAGE_MODEL = ImageModel.GPT_IMAGE_2
    }

    private fun requireClientReady(): OpenAIClient = OpenAIClientProvider.get(project)

    fun generateImage(
        promptText: String,
        requestedExtension: String? = null,
        sourceImagePath: String? = null,
        maskPath: String? = null,
    ): String {
        val image =
            if (sourceImagePath.isNullOrBlank()) {
                generateNewImage(promptText, requestedExtension)
            } else {
                editImage(
                    promptText = promptText,
                    requestedExtension = requestedExtension,
                    sourceImagePath = sourceImagePath,
                    maskPath = maskPath,
                )
            }
        image
            .url()
            .orElse(null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        image.b64Json().orElse(null)?.takeIf { it.isNotBlank() }?.let {
            return "data:${mimeTypeFor(requestedExtension)};base64,$it"
        }

        throw IllegalStateException("Image API returned neither url nor base64 image data")
    }

    private fun generateNewImage(
        promptText: String,
        requestedExtension: String?,
    ): Image {
        val params =
            ImageGenerateParams
                .builder()
                .prompt(promptText)
                .size(DEFAULT_GENERATE_IMAGE_SIZE)
                .model(DEFAULT_GENERATE_IMAGE_MODEL)
                .outputFormat(generateOutputFormatForExtension(requestedExtension))
                .build()
        return requireClientReady()
            .images()
            .generate(params)
            .data()
            .orElseThrow()
            .firstOrNull()
            ?: throw IllegalStateException("Image API returned no image entries")
    }

    private fun editImage(
        promptText: String,
        requestedExtension: String?,
        sourceImagePath: String,
        maskPath: String?,
    ): Image {
        val sourcePath = PathUtils.resolveWithinProject(project, sourceImagePath)
        val maskResolved =
            maskPath?.trim()?.takeIf { it.isNotBlank() }?.let { PathUtils.resolveWithinProject(project, it) }

        Files.newInputStream(sourcePath).use { sourceStream ->
            val imagePart =
                MultipartField
                    .builder<ImageEditParams.Image>()
                    .value(ImageEditParams.Image.ofInputStream(sourceStream))
                    .contentType(Files.probeContentType(sourcePath) ?: mimeTypeFor(sourcePath.fileName.toString()))
                    .filename(sourcePath.fileName.toString())
                    .build()

            val builder =
                ImageEditParams
                    .builder()
                    .image(imagePart)
                    .prompt(promptText)
                    .model(DEFAULT_EDIT_IMAGE_MODEL)
                    .n(1)
                    .size(DEFAULT_EDIT_IMAGE_SIZE)

            if (maskResolved != null) {
                Files.newInputStream(maskResolved).use { maskStream ->
                    builder.mask(buildMaskPart(maskResolved, maskStream))
                    return requireClientReady()
                        .images()
                        .edit(builder.build())
                        .data()
                        .orElseThrow()
                        .firstOrNull()
                        ?: throw IllegalStateException("Image edit API returned no image entries")
                }
            }

            return requireClientReady()
                .images()
                .edit(builder.build())
                .data()
                .orElseThrow()
                .firstOrNull()
                ?: throw IllegalStateException("Image edit API returned no image entries")
        }
    }

    private fun buildMaskPart(
        maskPath: java.nio.file.Path,
        maskStream: InputStream,
    ): MultipartField<InputStream> =
        MultipartField
            .builder<InputStream>()
            .value(maskStream)
            .contentType(Files.probeContentType(maskPath) ?: mimeTypeFor(maskPath.fileName.toString()))
            .filename(maskPath.fileName.toString())
            .build()

    private fun generateOutputFormatForExtension(extension: String?): ImageGenerateParams.OutputFormat =
        when (extension?.trim()?.lowercase()) {
            "jpg", "jpeg" -> ImageGenerateParams.OutputFormat.JPEG
            "webp" -> ImageGenerateParams.OutputFormat.WEBP
            else -> ImageGenerateParams.OutputFormat.PNG
        }

    private fun mimeTypeFor(extension: String?): String =
        when (extension?.trim()?.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
}
