package com.github.quanta_dance.quanta.plugins.intellij.tools.models

data class SearchProjectEmbeddingsResult(val results: List<Item>) {
    data class Item(val id: String, val score: Double, val metadata: Map<String, String>, val snippet: String)
}
