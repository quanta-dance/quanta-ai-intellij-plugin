// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolExecutionServiceTest {
    @Test
    fun `returns an async coordination handoff only when the tool explicitly requests it`() {
        assertEquals(
            "Queued 3 tracked agent tasks.",
            asyncCoordinationHandoffMessage(
                mapOf(
                    "handoffToAsyncCoordination" to true,
                    "message" to "Queued 3 tracked agent tasks.",
                ),
            ),
        )
        assertNull(
            asyncCoordinationHandoffMessage(
                mapOf(
                    "handoffToAsyncCoordination" to false,
                    "message" to "Ignored",
                ),
            ),
        )
        assertNull(asyncCoordinationHandoffMessage(mapOf("handoffToAsyncCoordination" to true)))
    }
}
