// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.project

import com.intellij.openapi.vfs.VirtualFile

object VersionUtil {
    fun safeVfsStamp(vf: VirtualFile): Long =
        try {
            vf.modificationStamp
        } catch (_: UnsupportedOperationException) {
            0L
        } catch (_: Throwable) {
            0L
        }

    // Never falls back to timeStamp; returns 0 if no suitable stamp is available
    fun computeVersion(
        psiStamp: Long?,
        docStamp: Long?,
        vfsStamp: Long?,
    ): Long {
        val p = (psiStamp ?: 0L)
        if (p > 0) return p
        val d = (docStamp ?: 0L)
        if (d > 0) return d
        val v = (vfsStamp ?: 0L)
        if (v > 0) return v
        return 0L
    }
}
