// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.services

import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIRetryableException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import kotlin.math.min

/**
 * Retry policy for an individual OpenAI SDK request during a transient service outage.
 *
 * Only failures that prove the request did not receive a usable response are retried. Retries may
 * continue for up to [MAX_RETRY_WINDOW_MILLIS], while any one retry delay is capped at
 * [MAX_SINGLE_BACKOFF_MILLIS]. This keeps automatic recovery active through a longer outage
 * without leaving an unbounded background loop.
 */
internal object OpenAIRequestRetryPolicy {
    const val MAX_RETRY_WINDOW_MILLIS = 15 * 60_000L
    private const val INITIAL_BACKOFF_MILLIS = 2_000L
    const val MAX_SINGLE_BACKOFF_MILLIS = 60_000L

    data class RetryPlan(
        val retryNumber: Int,
        val delayMillis: Long,
    )

    fun isRetryable(error: Throwable): Boolean =
        error.causeSequence().any { cause ->
            cause is IOException ||
                cause is SocketTimeoutException ||
                cause is HttpTimeoutException ||
                cause is HttpConnectTimeoutException ||
                cause is OpenAIIoException ||
                cause is OpenAIRetryableException
        }

    fun nextRetry(
        retryNumber: Int,
        backoffSpentMillis: Long,
    ): RetryPlan? {
        val remainingMillis = MAX_RETRY_WINDOW_MILLIS - backoffSpentMillis
        if (remainingMillis <= 0L) return null

        val exponentialDelayMillis =
            min(
                INITIAL_BACKOFF_MILLIS * (1L shl (retryNumber - 1).coerceAtMost(5)),
                MAX_SINGLE_BACKOFF_MILLIS,
            )
        return RetryPlan(
            retryNumber = retryNumber,
            delayMillis = min(exponentialDelayMillis, remainingMillis),
        )
    }

    private fun Throwable.causeSequence(): Sequence<Throwable> =
        generateSequence(this) { cause -> cause.cause?.takeUnless { it === cause } }
}
