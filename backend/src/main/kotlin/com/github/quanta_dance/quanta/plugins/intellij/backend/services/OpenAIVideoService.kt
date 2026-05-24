// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.github.quanta_dance.quanta.plugins.intellij.backend.openai.OpenAIClientProvider
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.openai.client.OpenAIClient
import com.openai.models.videos.Video
import com.openai.models.videos.VideoCreateParams
import com.openai.models.videos.VideoDownloadContentParams
import com.openai.models.videos.VideoModel
import com.openai.models.videos.VideoSeconds
import com.openai.models.videos.VideoSize
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class OpenAIVideoService(
    private val project: Project,
) {
    companion object {
        private val DEFAULT_VIDEO_MODEL = VideoModel.SORA_2
        private const val DEFAULT_POLL_INTERVAL_MILLIS = 10_000L
        private val SUPPORTED_VIDEO_SECONDS = setOf("4", "8", "12")
    }

    private fun requireClientReady(): OpenAIClient = OpenAIClientProvider.get(project)

    fun generateVideo(
        promptText: String,
        outputPath: Path,
        seconds: String? = null,
        size: String? = null,
        pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    ): Video {
        val paramsBuilder =
            VideoCreateParams
                .builder()
                .model(DEFAULT_VIDEO_MODEL)
                .prompt(promptText)

        seconds
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { requestedSeconds ->
                require(requestedSeconds in SUPPORTED_VIDEO_SECONDS) {
                    "Unsupported video duration '$requestedSeconds'. Supported values are: ${
                        SUPPORTED_VIDEO_SECONDS.joinToString(
                            ", "
                        )
                    }."
                }
                paramsBuilder.seconds(VideoSeconds.of(requestedSeconds))
            }
        size
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { paramsBuilder.size(VideoSize.of(it)) }

        val videos = requireClientReady().videos()
        var video = videos.create(paramsBuilder.build())
        while (video.status() != Video.Status.COMPLETED && video.status() != Video.Status.FAILED) {
            Thread.sleep(pollIntervalMillis)
            video = videos.retrieve(video.id())
        }

        if (video.status() == Video.Status.FAILED) {
            val errorText = video.error().map { it.toString() }.orElse("Video creation failed")
            throw IllegalStateException(errorText)
        }

        Files.createDirectories(outputPath.parent)
        videos
            .downloadContent(
                VideoDownloadContentParams
                    .builder()
                    .videoId(video.id())
                    .build(),
            ).body()
            .use { input ->
                Files.newOutputStream(outputPath).use { output ->
                    input.copyTo(output)
                }
            }
        return video
    }
}
