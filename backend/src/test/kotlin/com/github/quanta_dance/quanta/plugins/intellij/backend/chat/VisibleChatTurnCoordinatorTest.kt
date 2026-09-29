// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VisibleChatTurnCoordinatorTest {
    @Test
    fun `serializes overlapping visible turns in submission order`() =
        runBlocking {
            val coordinator = VisibleChatTurnCoordinator()
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()

            val first =
                async {
                    coordinator.run {
                        events += "first-start"
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                        events += "first-end"
                    }
                }
            firstStarted.await()

            val second =
                async {
                    coordinator.run {
                        events += "second-start"
                        events += "second-end"
                    }
                }

            assertEquals(listOf("first-start"), events)
            releaseFirst.complete(Unit)
            awaitAll(first, second)

            assertEquals(
                listOf("first-start", "first-end", "second-start", "second-end"),
                events,
            )
        }

    @Test
    fun `releases the next turn when the active turn fails`() =
        runBlocking {
            val coordinator = VisibleChatTurnCoordinator()
            val events = mutableListOf<String>()

            assertFailsWith<IllegalStateException> {
                coordinator.run {
                    events += "failed-turn"
                    error("expected test failure")
                }
            }
            coordinator.run { events += "next-turn" }

            assertEquals(listOf("failed-turn", "next-turn"), events)
        }
}
