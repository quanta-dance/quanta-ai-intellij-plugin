// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.listeners

import com.github.quanta_dance.quanta.plugins.intellij.services.OpenAIPrewarmService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class QuantaAIApplicationActivationListener : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Prewarm OpenAI client and DNS on project open to reduce first-turn latency
        project.service<OpenAIPrewarmService>().prewarm()
    }
}
