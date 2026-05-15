// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.intellij.openapi.components.Service
import com.openai.models.images.ImageGenerateParams
import com.openai.models.images.ImageModel

/**
 * Thin image-generation helper kept separate from the chat orchestration service.
 */
@Service(Service.Level.PROJECT)
class OpenAIImageService(
    private val requireClientReady: () -> com.openai.client.OpenAIClient,
) {
    fun generateImage(promptText: String): String {
        val params =
            ImageGenerateParams
                .builder()
                .prompt(promptText)
                .size(ImageGenerateParams.Size._1024X1024)
                .model(ImageModel.DALL_E_3)
                .build()
        return requireClientReady()
            .images()
            .generate(params)
            .data()
            .orElseThrow()
            .stream()
            .flatMap { image -> image.url().stream() }
            .findFirst()
            .orElseThrow()
    }
}
