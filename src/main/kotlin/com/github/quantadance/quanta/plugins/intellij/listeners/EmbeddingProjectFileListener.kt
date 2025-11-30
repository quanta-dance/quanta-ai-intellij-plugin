// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.listeners

import com.github.quantadance.quanta.plugins.intellij.project.EmbeddingManager
import com.github.quantadance.quanta.plugins.intellij.services.QDLog
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.util.messages.MessageBusConnection

@Service(Service.Level.PROJECT)
class EmbeddingProjectFileListener(private val project: Project) {
    private val connection: MessageBusConnection = project.messageBus.connect()
    private val logger = Logger.getInstance(EmbeddingProjectFileListener::class.java)

    init {
        connection.subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) {
                    try {
                        val virtualFile = FileDocumentManager.getInstance().getFile(document)
                        if (virtualFile != null && project.basePath != null) {
                            val projectPath = project.basePath!!
                            val relPath =
                                java.nio.file.Paths.get(projectPath).relativize(java.nio.file.Paths.get(virtualFile.path))
                                    .toString()
                            val text = document.text
                            EmbeddingManager.getInstance(project).enqueueFileForIndexing(relPath, text)
                        }
                    } catch (t: Throwable) {
                        QDLog.error(logger, { "Failed to enqueue file for embedding" }, t)
                    }
                }
            },
        )
    }

    fun dispose() {
        connection.disconnect()
    }

    companion object {
        fun getInstance(project: Project): EmbeddingProjectFileListener = project.service()
    }
}
