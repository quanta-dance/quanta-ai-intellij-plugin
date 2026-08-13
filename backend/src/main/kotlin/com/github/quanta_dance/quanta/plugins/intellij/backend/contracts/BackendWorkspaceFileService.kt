// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.contracts

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileReadRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileReadResult
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileService
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileWriteRequest
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.WorkspaceFileWriteResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Backend implementation of the shared workspace file service.
 *
 * It resolves and accesses files inside the backend process so frontend callers can use file
 * operations safely in split-mode and remote environments.
 */
class BackendWorkspaceFileService : WorkspaceFileService {
    override suspend fun read(request: WorkspaceFileReadRequest): WorkspaceFileReadResult =
        ApplicationManager.getApplication().runReadAction<WorkspaceFileReadResult> {
            val resolved =
                try {
                    resolvePath(request.path)
                } catch (e: IllegalArgumentException) {
                    return@runReadAction WorkspaceFileReadResult(
                        false,
                        "",
                        e.message ?: "Invalid path",
                        source = "backend",
                    )
                }

            val virtualFile =
                LocalFileSystem.getInstance().findFileByPath(resolved.toString())
                    ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(resolved.toString())
                    ?: return@runReadAction WorkspaceFileReadResult(false, "", "File not found", source = "backend")

            val content =
                FileDocumentManager.getInstance().getDocument(virtualFile)?.text
                    ?: VfsUtilCore.loadText(virtualFile)

            WorkspaceFileReadResult(success = true, content = content, source = "backend")
        }

    override suspend fun write(request: WorkspaceFileWriteRequest): WorkspaceFileWriteResult {
        val resolved =
            try {
                resolvePath(request.path)
            } catch (e: IllegalArgumentException) {
                return WorkspaceFileWriteResult(false, e.message ?: "Invalid path", source = "backend")
            }

        val parent =
            resolved.parent ?: return WorkspaceFileWriteResult(false, "Parent directory not found", source = "backend")
        LocalFileSystem.getInstance().refreshAndFindFileByPath(parent.toString())
        java.nio.file.Files
            .createDirectories(parent)
        java.nio.file.Files
            .writeString(resolved, request.content, StandardCharsets.UTF_8)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(resolved.toString())
        return WorkspaceFileWriteResult(success = true, source = "backend")
    }

    private fun resolvePath(rawPath: String): Path {
        val trimmed = rawPath.trim()
        require(trimmed.isNotBlank()) { "Path is not specified." }

        return Paths.get(trimmed).toAbsolutePath().normalize()
    }
}
