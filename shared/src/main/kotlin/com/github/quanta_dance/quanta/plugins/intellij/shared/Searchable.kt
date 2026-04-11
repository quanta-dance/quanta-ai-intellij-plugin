package com.github.quanta_dance.quanta.plugins.intellij.shared

/**
 * Represents an entity that can be filtered by a search query.
 */
interface Searchable {
    fun matches(query: String): Boolean
}