// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the localhost-only Quanta ACP advertisement for every open project. */
class QuantaAcpLocalDiscoveryStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<QuantaAcpShareService>()
    }
}
