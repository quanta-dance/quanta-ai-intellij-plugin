// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.APP, Service.Level.PROJECT)
class QuantaAIPluginDisposable() : Disposable {
    companion object {
        fun getInstance(): Disposable {
            return ApplicationManager.getApplication().getService(QuantaAIPluginDisposable::class.java)
        }

        fun getInstance(project: Project): Disposable {
            return project.getService(QuantaAIPluginDisposable::class.java)
        }
    }

    override fun dispose() {
        // Add dispose logic here if needed
    }
}
