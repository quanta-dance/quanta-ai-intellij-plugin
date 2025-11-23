package com.github.quanta_dance.quanta.plugins.intellij.tools.models

data class SearchInFilesResult(val matches: List<Match>, val modelSummary: String? = null) {
    data class Match(val path: String, val line: Int, val snippet: String)
}
