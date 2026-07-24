// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.system

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class TerminalCommandJobService(
    project: Project,
) : AutoCloseable {
    private val manager =
        TerminalCommandJobManager(
            baseDirectory = project.basePath?.let(Path::of),
            tempRoot = Files.createTempDirectory("quanta-terminal-jobs-"),
            consoleSink = { _, _ -> },
        )

    internal fun manager(): TerminalCommandJobManager = manager

    override fun close() {
        manager.close()
    }
}
