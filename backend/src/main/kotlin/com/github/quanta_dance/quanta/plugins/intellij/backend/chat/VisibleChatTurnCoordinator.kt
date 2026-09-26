// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes visible manager work for one chat service.
 *
 * A conversation must have exactly one active manager turn: concurrent turns can otherwise build
 * prompts from the same partial history, replace each other's thinking indicators, and append
 * responses out of order.
 */
internal class VisibleChatTurnCoordinator {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
