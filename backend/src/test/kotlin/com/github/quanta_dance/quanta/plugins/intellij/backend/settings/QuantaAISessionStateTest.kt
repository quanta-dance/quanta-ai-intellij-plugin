// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.settings

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuantaAISessionStateTest {
    @Test
    fun `serialized session state keeps chat and agent persistence but not frontend owned runtime fields`() {
        val json =
            Json.encodeToString(
                QuantaAISessionState.State(
                    agents = mutableListOf(QuantaAISessionState.AgentProfile(id = "a1", role = "developer")),
                    conversations =
                        mutableMapOf(
                            "main" to
                                mutableListOf(
                                    QuantaAISessionState.PersistedMessage(
                                        timestamp = 1L,
                                        role = "user",
                                        text = "hello",
                                    ),
                                ),
                        ),
                    conversationSummaries = mutableMapOf("main" to "summary"),
                    agentInboxes =
                        mutableMapOf(
                            "a1" to
                                mutableListOf(
                                    QuantaAISessionState.AgentInboxMessage(
                                        timestamp = 2L,
                                        from = "manager",
                                        text = "ping",
                                        kind = "note",
                                    ),
                                ),
                        ),
                    mainLastResponseId = "resp-1",
                ),
            )

        assertTrue(json.contains("\"agents\""))
        assertTrue(json.contains("\"conversations\""))
        assertTrue(json.contains("\"conversationSummaries\""))
        assertTrue(json.contains("\"agentInboxes\""))
        assertTrue(json.contains("\"mainLastResponseId\""))
        assertFalse(json.contains("agenticEnabled"))
        assertFalse(json.contains("maxAutomaticTurns"))
        assertFalse(json.contains("openAiToken"))
    }
}
