// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

/**
 * Resolves the storage key for a session plan.
 *
 * Session plans must be isolated per chat session so switching between saved chats does not leak or
 * overwrite plan state. We still include the branch-aware main conversation key to avoid collisions
 * across branches, then scope within that by active chat session id.
 */
class SessionPlanKeyResolver(
    private val mainConversationKeyProvider: () -> String,
    private val activeSessionIdProvider: () -> String?,
) {
    fun currentKey(): String {
        val conversationKey = mainConversationKeyProvider().trim().ifBlank { "main@no-branch" }
        val sessionId = activeSessionIdProvider()?.trim().orEmpty().ifBlank { "default" }
        return "$conversationKey|session:$sessionId"
    }
}
