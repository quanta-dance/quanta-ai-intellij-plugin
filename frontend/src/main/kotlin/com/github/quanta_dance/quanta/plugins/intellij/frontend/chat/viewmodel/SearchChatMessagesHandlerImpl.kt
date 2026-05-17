// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.SearchState
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.hasResults
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.isSearching
import com.github.quanta_dance.quanta.plugins.intellij.frontend.ui.searchQuery
import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Implementation of the SearchChatMessagesHandler interface for handling
 * chat message search functionality and state management.
 *
 * This class provides the ability to search through a list of chat messages,
 * navigate through search results, and manage the state of the search process.
 *
 * @property messagesFlow A StateFlow of a List containing ChatMessage objects.
 *                        It represents the stream of chat messages to be searched.
 * @property searchStateFlow A StateFlow representing the current search state,
 *                           which can be idle, searching, or showing search results.
 */
class SearchChatMessagesHandlerImpl(
    private val messagesFlow: StateFlow<List<ChatMessage>> = MutableStateFlow(emptyList()),
    coroutineScope: CoroutineScope,
) : SearchChatMessagesHandler {
    private val _searchStateFlow: MutableStateFlow<SearchState> = MutableStateFlow(SearchState.Idle)

    override val searchStateFlow: StateFlow<SearchState> = _searchStateFlow.asStateFlow()

    init {
        messagesFlow
            .onEach { _ ->
                // When new messages are received, refresh search results using the latest query.
                // If no search query is available(search is not open), skip the operation.
                val searchState = _searchStateFlow.value
                if (searchState.isSearching) {
                    searchState.searchQuery?.let { query -> onSearchQuery(query) }
                }
            }.launchIn(coroutineScope)
    }

    override fun onStartSearch() {
        _searchStateFlow.value = SearchState.Searching("")
    }

    override fun onStopSearch() {
        _searchStateFlow.value = SearchState.Idle
    }

    override fun onSearchQuery(query: String) {
        val messages = messagesFlow.value

        performSearch(query, messages)
    }

    override fun onNavigateToNextSearchResult() {
        val searchState = _searchStateFlow.value
        if (searchState !is SearchState.SearchResults) return
        if (!(searchState.hasResults)) return

        moveSearchResultSelectionToIndex(searchState, searchState.currentSelectedSearchResultIndex + 1)
    }

    override fun onNavigateToPreviousSearchResult() {
        val searchState = _searchStateFlow.value
        if (searchState !is SearchState.SearchResults) return
        if (!(searchState.hasResults)) return

        moveSearchResultSelectionToIndex(searchState, searchState.currentSelectedSearchResultIndex - 1)
    }

    private fun moveSearchResultSelectionToIndex(
        searchState: SearchState.SearchResults,
        newSearchResultIndex: Int,
    ) {
        val nextSearchResultIndex =
            when {
                newSearchResultIndex < 0 -> searchState.searchResultIds.lastIndex
                newSearchResultIndex > searchState.searchResultIds.lastIndex -> 0
                else -> newSearchResultIndex
            }

        _searchStateFlow.value = searchState.copy(currentSelectedSearchResultIndex = nextSearchResultIndex)
    }

    private fun performSearch(
        query: String,
        messages: List<ChatMessage>,
    ) {
        val matchingIds =
            messages
                .filter { message -> message.matches(query) }
                .map { it.id }

        _searchStateFlow.value =
            SearchState.SearchResults(
                query = query,
                searchResultIds = matchingIds,
                currentSelectedSearchResultIndex = if (matchingIds.isNotEmpty()) 0 else -1,
            )
    }
}
