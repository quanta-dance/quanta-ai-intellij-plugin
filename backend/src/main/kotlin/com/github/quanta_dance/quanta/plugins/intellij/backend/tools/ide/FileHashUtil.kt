// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.ide

import java.security.MessageDigest

internal object FileHashUtil {
    fun sha256Normalized(text: String): String {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(normalized.toByteArray()).joinToString("") { b -> "%02x".format(b) }
    }
}
