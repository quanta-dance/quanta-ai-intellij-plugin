// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.models

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedRpcSerializationTest {
    private val json = Json

    @Test
    fun `collaboration participant round trips all populated fields`() {
        val participant =
            CollaborationParticipantDto(
                id = "local:agent-1",
                displayName = "Reviewer",
                kind = CollaborationParticipantKindDto.LOCAL_AGENT,
                capabilities = setOf(CollaborationCapabilityDto.TASKS, CollaborationCapabilityDto.RESULTS),
                availability = CollaborationAvailabilityDto.BUSY,
                transportId = "agent-1",
            )

        assertEquals(participant, json.decodeFromString(json.encodeToString(participant)))
    }

    @Test
    fun `collaboration participant accepts omitted fields introduced after older clients`() {
        val participant =
            json.decodeFromString<CollaborationParticipantDto>(
                """{"id":"manager:chat-1","displayName":"Manager","kind":"MANAGER"}""",
            )

        assertEquals(emptySet(), participant.capabilities)
        assertEquals(CollaborationAvailabilityDto.UNKNOWN, participant.availability)
        assertEquals(null, participant.transportId)
    }
}
