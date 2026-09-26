// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import com.github.quanta_dance.quanta.plugins.intellij.shared.contracts.ChatMessage

/** Returns whether a visible message belongs to the main manager conversation sent to the model. */
internal fun isMainConversationHistoryMessage(message: ChatMessage): Boolean =
    message.isMyMessage || (message.isTextMessage() && message.author == "Quanta AI")
