// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.models

data class SearchInFilesResult(val matches: List<Match>, val modelSummary: String? = null) {
    data class Match(val path: String, val line: Int, val snippet: String)
}
