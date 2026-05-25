// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service

/**
 * Deprecated empty compatibility disposable.
 *
 * Prefer implementing [Disposable] directly on services that own resources so plugin unload can
 * clean them up through IntelliJ-managed service disposal.
 */
@Service(Service.Level.APP, Service.Level.PROJECT)
class QuantaAIPluginDisposable : Disposable {
    override fun dispose() {
        // Add dispose logic here if needed
    }
}
