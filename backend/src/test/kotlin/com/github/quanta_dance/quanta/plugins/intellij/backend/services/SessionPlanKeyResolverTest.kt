// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionPlanKeyResolverTest {
    @Test
    fun `includes active session id in the plan storage key`() {
        val resolver =
            SessionPlanKeyResolver(
                mainConversationKeyProvider = { "main@feature_branch" },
                activeSessionIdProvider = { "session-123" },
            )

        assertEquals("main@feature_branch|session:session-123", resolver.currentKey())
    }

    @Test
    fun `falls back when session id is unavailable`() {
        val resolver =
            SessionPlanKeyResolver(
                mainConversationKeyProvider = { "main@feature_branch" },
                activeSessionIdProvider = { null },
            )

        assertEquals("main@feature_branch|session:default", resolver.currentKey())
    }
}
