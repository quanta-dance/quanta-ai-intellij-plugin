// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat.viewmodel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatMessageRefreshPolicyTest {
    @Test
    fun `fetches messages when no snapshot has been cached`() {
        assertTrue(ChatMessageRefreshPolicy.shouldRefresh(cachedRevision = null, backendRevision = 0))
    }

    @Test
    fun `skips unchanged message session and plan snapshots`() {
        assertFalse(ChatMessageRefreshPolicy.shouldRefresh(cachedRevision = 42, backendRevision = 42))
        assertFalse(ChatMessageRefreshPolicy.shouldRefresh(cachedRevision = 7, backendRevision = 7))
        assertFalse(ChatMessageRefreshPolicy.shouldRefresh(cachedRevision = 3, backendRevision = 3))
    }

    @Test
    fun `fetches snapshots when the backend revision changes or is unavailable`() {
        assertTrue(ChatMessageRefreshPolicy.shouldRefresh(cachedRevision = 42, backendRevision = 43))
        assertTrue(ChatMessageRefreshPolicy.shouldRefresh(cachedRevision = 42, backendRevision = null))
    }
}
