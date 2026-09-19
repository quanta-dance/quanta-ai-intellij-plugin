// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import java.io.IOException
import java.net.http.HttpTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAIRequestRetryPolicyTest {
    @Test
    fun `retries transport failures for fifteen minutes with one-minute maximum interval`() {
        var spentMillis = 0L
        val delays =
            buildList {
                var retryNumber = 1
                while (true) {
                    val plan = OpenAIRequestRetryPolicy.nextRetry(retryNumber, spentMillis) ?: break
                    add(plan.delayMillis)
                    spentMillis += plan.delayMillis
                    retryNumber += 1
                }
            }

        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L), delays.take(6))
        assertEquals(OpenAIRequestRetryPolicy.MAX_SINGLE_BACKOFF_MILLIS, delays[6])
        assertTrue(delays.drop(6).all { it <= OpenAIRequestRetryPolicy.MAX_SINGLE_BACKOFF_MILLIS })
        assertEquals(OpenAIRequestRetryPolicy.MAX_RETRY_WINDOW_MILLIS, spentMillis)
        assertEquals(58_000L, delays.last())
        assertNull(OpenAIRequestRetryPolicy.nextRetry(delays.size + 1, spentMillis))
    }

    @Test
    fun `classifies network and timeout failures as retryable`() {
        assertTrue(OpenAIRequestRetryPolicy.isRetryable(IOException("connection reset")))
        assertTrue(OpenAIRequestRetryPolicy.isRetryable(RuntimeException(HttpTimeoutException("timed out"))))
        assertFalse(OpenAIRequestRetryPolicy.isRetryable(IllegalArgumentException("invalid request")))
    }
}
