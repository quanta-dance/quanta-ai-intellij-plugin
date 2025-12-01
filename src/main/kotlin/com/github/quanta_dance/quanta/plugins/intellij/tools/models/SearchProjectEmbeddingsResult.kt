// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.tools.models

data class SearchProjectEmbeddingsResult(val results: List<Item>) {
    data class Item(val id: String, val score: Double, val metadata: Map<String, String>, val snippet: String)
}
