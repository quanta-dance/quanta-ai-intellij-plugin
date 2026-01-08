// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.media

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.quanta_dance.quanta.plugins.intellij.services.AIVoiceService
import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.github.quanta_dance.quanta.plugins.intellij.services.ToolWindowService
import com.github.quanta_dance.quanta.plugins.intellij.tools.PathUtils
import com.github.quanta_dance.quanta.plugins.intellij.tools.ToolInterface
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture

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

        val projectBase = project.basePath ?: return "Project base path not found."
        val resolved =
            try {
                PathUtils.resolveWithinProject(projectBase, filePath)
            } catch (e: IllegalArgumentException) {
                project.service<ToolWindowService>().addToolingMessage("Modify File - rejected", e.message ?: "Invalid path")
                QDLog.warn(logger, { "Invalid path for CreateOrUpdateFile: $filePath" }, e)
                return e.message ?: "Invalid path"
            }

        val voiceService = project.service<AIVoiceService>()

        // Use a CompletableFuture to wait for the speech InputStream and write it to disk
        val writeFuture = CompletableFuture<Void>()

        try {
            voiceService.speech(t) { inputStream ->
                // write inputStream to file
                try {
                    val ioFile = resolved.toFile()
                    ioFile.parentFile?.mkdirs()
                    ioFile.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }
                    BufferedInputStream(inputStream).use { bis ->
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
                    QDLog.info(logger) { "Saved speech MP3 to: $ioFile" }
                    writeFuture.complete(null)
                } catch (e: IOException) {
                    writeFuture.completeExceptionally(e)
                }
            }.exceptionally { ex ->
                writeFuture.completeExceptionally(ex)
                null
            }

            // wait for completion
            writeFuture.join()
            // Optionally notify tool window
            try {
                val toolService = project.service<ToolWindowService>()
                toolService.addToolingMessage("Sound generated", "Saved to: $resolved")
            } catch (_: Throwable) {
                // ignore if tool window service not available
            }

            return resolved.toString()
        } catch (e: Exception) {
            QDLog.error(logger, { "Failed to generate or save speech" }, e)
            throw RuntimeException("Failed to generate or save speech: ${e.message}", e)
        }
    }
}
